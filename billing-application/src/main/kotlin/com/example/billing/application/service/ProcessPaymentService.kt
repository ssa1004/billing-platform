package com.example.billing.application.service

import com.example.billing.application.command.ProcessPaymentCommand
import com.example.billing.application.exception.OrderNotFoundException
import com.example.billing.application.exception.PaymentNotFoundException
import com.example.billing.application.port.`in`.ProcessPaymentUseCase
import com.example.billing.application.port.out.EventPublisher
import com.example.billing.application.port.out.OrderRepository
import com.example.billing.application.port.out.PaymentRepository
import com.example.billing.application.port.out.PgClient
import com.example.billing.domain.order.OrderId
import com.example.billing.domain.payment.Payment
import com.example.billing.domain.payment.PaymentId
import com.example.billing.domain.shared.Money
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * 결제 처리 use case — 외부 PG (결제 게이트웨이) 호출 + Order 상태 천이.
 *
 * **왜 3단계로 쪼개나 (외부 호출을 트랜잭션 밖으로 빼는 이유)**: 외부 PG 호출은 응답까지
 * 수초가 걸릴 수 있는데, 그 동안 DB 트랜잭션을 열어둔 채 기다리면 그 트랜잭션이 점유한
 * connection 이 pool 에서 빠지지 않습니다. 트래픽이 몰리는 시점에 PG 가 슬로우다운되면 결제
 * 트랜잭션들이 connection 을 다 차지해 다른 도메인 (Wallet, Invoice 등) 의 트랜잭션까지 같이
 * 멈추는 cascade 가 됩니다. 이를 막기 위해 흐름을 셋으로 쪼갭니다:
 *  1. **Phase 1 (DB tx, 짧음)**: Idempotency-Key 점유 + Order 로드 + PENDING 상태 Payment
 *     row INSERT → commit. 외부 호출 없으니 connection 을 길게 잡지 않음.
 *  2. **PG 호출 (트랜잭션 밖)**: connection 을 잡지 않은 상태로 PG 응답 대기. Resilience4j
 *     서킷브레이커 / Retry / 단축 timeout 으로 보호.
 *  3. **Phase 2 (DB tx, 짧음)**: PG 결과를 반영해 Payment / Order 상태 천이 + 이벤트 발행
 *     → commit.
 *
 * **왜 Idempotency-Key 가 Phase 1 commit 이후엔 안 풀리는가 (의도)**: Phase 1 이 commit
 * 된 시점부터 PG 호출이 이미 시작 됐을 수 있습니다. 같은 키로 또 호출이 들어오면 PG 에 결제가
 * 두 번 박힐 위험이 있어 키 점유를 그대로 유지 (TTL ~24h). 호출자는 같은 idempotencyKey 로
 * 결과를 GET 해서 상태를 조회하는 패턴 (REST 표준 idempotency 패턴과 동일).
 *
 * **실패 시나리오**:
 *  - Phase 1 실패 — 예: [OrderNotFoundException]. tx rollback 으로 Idempotency-Key
 *    자동 release → 호출자가 같은 키로 재시도 가능 (PG 호출은 아직 안 일어남).
 *  - Phase 2 실패 — Payment 가 PENDING 으로 남고 PG 는 이미 처리한 상태가 될 수 있음.
 *    이 case 는 별도 reconciler 가 PG 에 같은 idempotencyKey 로 조회 (lookup) 해서 상태를
 *    동기화 (운영 보강 영역, [ReconcilePgFailuresService]).
 */
@Service
open class ProcessPaymentService(
    private val orders: OrderRepository,
    private val payments: PaymentRepository,
    private val pgClient: PgClient,
    private val events: EventPublisher,
    private val idempotency: IdempotentExecution,
    private val clock: Clock,
    txManager: PlatformTransactionManager,
) : ProcessPaymentUseCase {

    private val tx = TransactionTemplate(txManager)

    override fun process(command: ProcessPaymentCommand): Payment {
        // Phase 1 — idempotency 점유 + PENDING Payment 영속화 (외부 호출 없음, 짧은 tx)
        val ctx = tx.execute { initiate(command) }!!

        // Phase 2 — PG 호출 (트랜잭션 밖)
        val pgResult = pgClient.authorize(
            PgClient.AuthorizeRequest(
                command.idempotencyKey, ctx.amount, command.method, ctx.orderId.toString(),
            ),
        )

        // Phase 3 — 결과 반영 + 이벤트 발행 (짧은 tx)
        return tx.execute { finalize(ctx, pgResult) }!!
    }

    private fun initiate(cmd: ProcessPaymentCommand): InitiatedContext {
        idempotency.acquireAndReleaseOnRollback(cmd.idempotencyKey)

        val order = orders.findById(cmd.orderId)
            .orElseThrow { OrderNotFoundException(cmd.orderId) }

        val payment = Payment.initiate(
            order.id, order.totalAmount, cmd.method,
            cmd.idempotencyKey, clock,
        )
        payments.save(payment)
        return InitiatedContext(payment.id, order.id, order.totalAmount)
    }

    private fun finalize(ctx: InitiatedContext, pgResult: PgClient.AuthorizeResult): Payment {
        val payment = payments.findById(ctx.paymentId)
            .orElseThrow { PaymentNotFoundException(ctx.paymentId) }
        val order = orders.findById(ctx.orderId)
            .orElseThrow { OrderNotFoundException(ctx.orderId) }

        if (pgResult.approved) {
            val approved = payment.approve(pgResult.pgTransactionId!!, clock)
            payments.save(payment)
            val paid = order.markPaid(payment.id.toString(), clock)
            orders.save(order)
            events.publish(approved)
            events.publish(paid)
            log.info("payment approved id={} order={}", payment.id, order.id)
        } else {
            val rejected = payment.reject(pgResult.errorCode!!, pgResult.errorMessage!!, clock)
            payments.save(payment)
            val failed = order.markFailed("payment rejected: ${pgResult.errorMessage}", clock)
            orders.save(order)
            events.publish(rejected)
            events.publish(failed)
            log.warn(
                "payment rejected id={} order={} code={}",
                payment.id, order.id, pgResult.errorCode,
            )
        }
        return payment
    }

    @JvmRecord
    private data class InitiatedContext(val paymentId: PaymentId, val orderId: OrderId, val amount: Money)

    companion object {
        private val log = LoggerFactory.getLogger(ProcessPaymentService::class.java)
    }
}
