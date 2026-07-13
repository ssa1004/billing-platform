package com.example.billing.application.service

import com.example.billing.application.command.RefundCommand
import com.example.billing.application.exception.OrderNotFoundException
import com.example.billing.application.exception.PaymentNotFoundException
import com.example.billing.application.exception.RefundAlreadyRequestedException
import com.example.billing.application.exception.RefundNotFoundException
import com.example.billing.application.port.`in`.AuditLogger
import com.example.billing.application.port.`in`.RefundUseCase
import com.example.billing.application.port.out.EventPublisher
import com.example.billing.application.port.out.OrderRepository
import com.example.billing.application.port.out.PaymentRepository
import com.example.billing.application.port.out.PgClient
import com.example.billing.application.port.out.RefundRepository
import com.example.billing.domain.audit.AuditAction
import com.example.billing.domain.audit.AuditActor
import com.example.billing.domain.order.OrderId
import com.example.billing.domain.payment.PaymentId
import com.example.billing.domain.refund.Refund
import com.example.billing.domain.refund.RefundId
import com.example.billing.domain.shared.Money
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * 환불 use case — PG 환불 호출 + Order / Refund 상태 갱신 + 이벤트 발행.
 * Wallet 환원은 RefundCompleted 이벤트의 컨슈머가 처리 (도메인 분리, decoupled).
 *
 * **왜 3단계로 쪼개나 (외부 호출을 트랜잭션 밖으로 빼는 이유)**:
 * [ProcessPaymentService] 와 같은 이유 — 외부 PG 호출 동안 DB 트랜잭션을 열어두면
 * connection 이 pool 에서 빠지지 않아, PG 가 슬로우다운되면 다른 도메인의 트랜잭션까지 같이
 * 멈추는 cascade 가 발생합니다. 흐름을 셋으로 쪼개 외부 호출 동안 connection 을 잡지 않게
 * 합니다:
 *  1. **Phase 1 (DB tx, 짧음)**: Idempotency-Key 점유 + Payment 로드 + REQUESTED 상태
 *     Refund row INSERT → commit.
 *  2. **PG 환불 호출 (트랜잭션 밖)**: connection 미점유 상태로 응답 대기. Resilience4j
 *     서킷브레이커 / Retry 로 보호.
 *  3. **Phase 2 (DB tx, 짧음)**: PG 결과를 반영해 Refund 상태 천이 + Order REFUNDED 마킹
 *     + 이벤트 발행 + audit log → commit.
 *
 * **Phase 2 실패 시**: Refund 가 REQUESTED 로 남고 PG 는 이미 환불 처리를 끝낸 상태가
 * 될 수 있습니다. 별도 reconciler 가 stuck REQUESTED row 를 시간 오래된 순으로 스캔해 PG 에
 * 같은 idempotencyKey 로 조회 → 실제 결과로 상태를 동기화 (운영 보강 영역,
 * [ReconcilePgFailuresService]; V11 migration 의 인덱스 `idx_refund_status_requested_at`
 * 가 그 reconciler 용).
 */
@Service
open class RefundService(
    private val payments: PaymentRepository,
    private val refunds: RefundRepository,
    private val orders: OrderRepository,
    private val pgClient: PgClient,
    private val events: EventPublisher,
    private val idempotency: IdempotentExecution,
    private val audit: AuditLogger,
    private val clock: Clock,
    txManager: PlatformTransactionManager,
) : RefundUseCase {

    private val tx = TransactionTemplate(txManager)

    override fun refund(command: RefundCommand): Refund {
        // Phase 1 — idempotency 점유 + REQUESTED Refund 영속화
        val ctx = tx.execute { initiate(command) }!!

        // Phase 2 — PG 환불 호출 (트랜잭션 밖)
        val pgResult = pgClient.refund(
            PgClient.RefundRequest(ctx.pgTransactionId, ctx.amount, command.reason),
        )

        // Phase 3 — 결과 반영 + 이벤트 + audit (짧은 tx)
        return tx.execute { finalize(command, ctx, pgResult) }!!
    }

    private fun initiate(cmd: RefundCommand): InitiatedContext {
        idempotency.acquireAndReleaseOnRollback(cmd.idempotencyKey)

        val payment = payments.findById(cmd.paymentId)
            .orElseThrow { PaymentNotFoundException(cmd.paymentId) }

        // 결제 단위 이중 환불 차단 — 같은 payment 에 활성(FAILED 아님) 환불이 이미 있으면 거절한다.
        // 요청 Idempotency-Key 는 "같은 요청의 재시도"만 막을 뿐, 다른 키로 오는 중복 환불은 못 막는다.
        if (refunds.existsActiveByPaymentId(payment.id)) {
            throw RefundAlreadyRequestedException(payment.id)
        }

        val refund = Refund.request(
            payment.id, payment.amount, cmd.reason,
            cmd.idempotencyKey, clock,
        )
        refunds.save(refund)
        return InitiatedContext(
            refund.id, payment.id,
            payment.pgTransactionId!!, payment.amount, payment.orderId,
        )
    }

    private fun finalize(
        cmd: RefundCommand,
        ctx: InitiatedContext,
        pgResult: PgClient.RefundResult,
    ): Refund {
        val refund = refunds.findById(ctx.refundId)
            .orElseThrow { RefundNotFoundException(ctx.refundId) }

        if (pgResult.approved) {
            val approved = refund.approve(pgResult.pgRefundId!!, clock)
            val completed = refund.complete(clock)
            refunds.save(refund)

            // Order 상태도 REFUNDED 로
            val order = orders.findById(ctx.orderId)
                .orElseThrow { OrderNotFoundException(ctx.orderId) }
            val orderRefunded = order.markRefunded(refund.id.toString(), clock)
            orders.save(order)

            events.publish(approved)
            events.publish(completed)
            events.publish(orderRefunded)

            // Audit — 환불 승인은 돈이 customer 로 빠져나가는 동작이라 audit 대상.
            // 회계 감사 시 "이 환불이 왜 승인됐는지" 답할 수 있어야 함.
            audit.log(
                AuditActor.system("refund-service"),
                AuditAction.REFUND_APPROVED,
                "Refund",
                refund.id.toString(),
                null,
                AuditPayloads.`object`()
                    .put("paymentId", ctx.paymentId)
                    .put("amount", refund.amount)
                    .put("pgRefundId", pgResult.pgRefundId)
                    .build(),
                cmd.reason,
            )

            log.info(
                "refund completed id={} payment={} amount={}",
                refund.id, ctx.paymentId, refund.amount,
            )
        } else {
            val failed = refund.fail(pgResult.errorMessage!!, clock)
            refunds.save(refund)
            events.publish(failed)

            audit.log(
                AuditActor.system("refund-service"),
                AuditAction.REFUND_FAILED,
                "Refund",
                refund.id.toString(),
                null,
                AuditPayloads.`object`()
                    .put("paymentId", ctx.paymentId)
                    .put("errorMessage", pgResult.errorMessage)
                    .build(),
                cmd.reason,
            )

            log.warn("refund failed id={} reason={}", refund.id, pgResult.errorMessage)
        }
        return refund
    }

    @JvmRecord
    private data class InitiatedContext(
        val refundId: RefundId,
        val paymentId: PaymentId,
        val pgTransactionId: String,
        val amount: Money,
        val orderId: OrderId,
    )

    companion object {
        private val log = LoggerFactory.getLogger(RefundService::class.java)
    }
}
