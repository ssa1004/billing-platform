package com.example.billing.application.service

import com.example.billing.application.exception.OrderNotFoundException
import com.example.billing.application.port.`in`.AuditLogger
import com.example.billing.application.port.`in`.ReconcilePgFailuresUseCase
import com.example.billing.application.port.out.EventPublisher
import com.example.billing.application.port.out.OrderRepository
import com.example.billing.application.port.out.PaymentRepository
import com.example.billing.application.port.out.PgClient
import com.example.billing.application.port.out.RefundRepository
import com.example.billing.domain.audit.AuditAction
import com.example.billing.domain.audit.AuditActor
import com.example.billing.domain.payment.PaymentStatus
import com.example.billing.domain.refund.RefundStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration

/**
 * PG-failure reconciler — 3-phase 결제/환불 흐름의 phase 3 (DB tx2) 이 깨져 우리 쪽이
 * PENDING/REQUESTED 로 stuck 된 row 들을 발견해 PG lookup 결과로 동기화.
 *
 * **흐름 — Payment**:
 *  1. `findStalePending(now - graceWindow)` 로 후보 fetch
 *  2. 각 후보에 대해 별도 트랜잭션 안에서:
 *     1. 현재 상태 재확인 (race — 다른 호출자가 이미 마감했으면 skip)
 *     2. `pgClient.lookup(idempotencyKey)` → APPROVED / REJECTED / NOT_FOUND / IN_PROGRESS
 *     3. 결과 반영: APPROVED → Payment.approve + Order.markPaid + 이벤트 발행
 *        REJECTED / NOT_FOUND → Payment.reject + Order.markFailed + 이벤트 발행
 *        IN_PROGRESS → 다음 사이클에 재시도 (no-op)
 *
 * **흐름 — Refund**: 같은 패턴이지만 idempotencyKey 가 null 인 옛날 row 는 lookup 불가
 * 라 SQL 단계에서 제외. RefundCompleted 이벤트의 Wallet 환원 컨슈머는 그대로 동작.
 *
 * **왜 별도 트랜잭션 (per-row)**: 한 후보의 상태 천이가 다른 후보를 막지 않아야 합니다.
 * 한 트랜잭션이 길어지면 lock 보유 시간 증가 + 한 row 가 실패하면 모두 rollback. row 단위
 * 격리로 partial progress.
 *
 * **graceWindow**: phase 1 commit 직후의 정상 동작 중인 Payment 까지 잡지 않도록
 * 충분히 큰 값 (default 5분) 으로 잡음. PG 호출 + tx2 정상 흐름은 보통 수 초.
 */
@Service
@ConditionalOnProperty(name = ["billing.pg.reconciler.enabled"], havingValue = "true")
open class ReconcilePgFailuresService(
    private val payments: PaymentRepository,
    private val refunds: RefundRepository,
    private val orders: OrderRepository,
    private val pgClient: PgClient,
    private val events: EventPublisher,
    private val audit: AuditLogger,
    private val clock: Clock,
    txManager: PlatformTransactionManager,
) : ReconcilePgFailuresUseCase {

    private val tx = TransactionTemplate(txManager)

    @Value("\${billing.pg.reconciler.batch-size:50}")
    private var batchSize: Int = 50

    @Value("\${billing.pg.reconciler.grace-minutes:5}")
    private var graceMinutes: Long = 5L

    override fun reconcileBatch(): Int {
        val staleBefore = clock.instant().minus(Duration.ofMinutes(graceMinutes))

        // 후보 조회는 readonly tx 와 다르게 분리하지 않고 그냥 select. 본 처리만 per-row tx.
        val stalePayments = payments.findStalePending(staleBefore, batchSize)
        val staleRefunds = refunds.findStaleRequested(staleBefore, batchSize)

        var processed = 0
        for (p in stalePayments) {
            try {
                if (reconcilePayment(p.idempotencyKey)) processed++
            } catch (e: RuntimeException) {
                // 한 row 실패가 다음 row 를 막지 않도록 격리. 로그만 남기고 다음 사이클에 재시도.
                log.warn("reconcile payment failed id={} key={}", p.id, p.idempotencyKey, e)
            }
        }
        for (r in staleRefunds) {
            try {
                if (reconcileRefund(r.idempotencyKey)) processed++
            } catch (e: RuntimeException) {
                log.warn("reconcile refund failed id={} key={}", r.id, r.idempotencyKey, e)
            }
        }
        if (processed > 0) {
            log.info(
                "pg reconcile cycle processed={} payments={} refunds={}",
                processed, stalePayments.size, staleRefunds.size,
            )
        }
        return processed
    }

    private fun reconcilePayment(idempotencyKey: String): Boolean {
        // PG lookup 은 트랜잭션 밖 — 외부 호출이 DB connection 점유 안 하도록.
        val lookup = pgClient.lookup(idempotencyKey)
        if (lookup.status == PgClient.LookupStatus.IN_PROGRESS) {
            // PG 가 아직 결과를 결정 못 함. 다음 사이클에 다시 시도.
            return false
        }
        val changed = tx.execute { applyPaymentLookup(idempotencyKey, lookup) }
        return changed == true
    }

    private fun applyPaymentLookup(idempotencyKey: String, lookup: PgClient.LookupResult): Boolean {
        val payment = payments.findByIdempotencyKey(idempotencyKey).orElse(null) ?: return false
        if (payment.status != PaymentStatus.PENDING) {
            // 다른 호출자가 이미 마감 — race, skip.
            return false
        }
        val order = orders.findById(payment.orderId)
            .orElseThrow { OrderNotFoundException(payment.orderId) }

        when (lookup.status) {
            PgClient.LookupStatus.APPROVED -> {
                val approved = payment.approve(lookup.pgReferenceId!!, clock)
                payments.save(payment)
                val paid = order.markPaid(payment.id.toString(), clock)
                orders.save(order)
                events.publish(approved)
                events.publish(paid)
                audit.log(
                    AuditActor.system("pg-reconciler"),
                    AuditAction.PAYMENT_RECONCILED,
                    "Payment", payment.id.toString(),
                    null,
                    AuditPayloads.`object`()
                        .put("resolution", "APPROVED")
                        .put("idempotencyKey", idempotencyKey)
                        .put("pgRef", lookup.pgReferenceId)
                        .build(),
                    "phase 3 retry via PG lookup",
                )
                log.info("reconciled payment APPROVED id={} key={}", payment.id, idempotencyKey)
            }
            PgClient.LookupStatus.REJECTED, PgClient.LookupStatus.NOT_FOUND -> {
                val code = lookup.errorCode
                    ?: if (lookup.status == PgClient.LookupStatus.NOT_FOUND) "PG_NOT_FOUND" else "PG_REJECTED"
                val msg = lookup.errorMessage ?: "reconciled by lookup"
                val rejected = payment.reject(code, msg, clock)
                payments.save(payment)
                val failed = order.markFailed("payment reconciled: $msg", clock)
                orders.save(order)
                events.publish(rejected)
                events.publish(failed)
                audit.log(
                    AuditActor.system("pg-reconciler"),
                    AuditAction.PAYMENT_RECONCILED,
                    "Payment", payment.id.toString(),
                    null,
                    AuditPayloads.`object`()
                        .put("resolution", lookup.status)
                        .put("idempotencyKey", idempotencyKey)
                        .put("errorCode", code)
                        .build(),
                    "phase 3 retry via PG lookup",
                )
                log.info("reconciled payment {} id={} key={}", lookup.status, payment.id, idempotencyKey)
            }
            PgClient.LookupStatus.IN_PROGRESS -> {
                // 위 reconcilePayment 에서 이미 걸렀지만 방어.
                return false
            }
        }
        return true
    }

    private fun reconcileRefund(idempotencyKey: String?): Boolean {
        if (idempotencyKey == null) return false
        val lookup = pgClient.lookup(idempotencyKey)
        if (lookup.status == PgClient.LookupStatus.IN_PROGRESS) return false

        val changed = tx.execute { applyRefundLookup(idempotencyKey, lookup) }
        return changed == true
    }

    private fun applyRefundLookup(idempotencyKey: String, lookup: PgClient.LookupResult): Boolean {
        // Refund 는 idempotency key 로 직접 조회하는 메서드가 아직 없으므로 stale fetch 했던
        // row 를 다시 fetch. select 시점과 reconcile 시점이 떨어져 있어 status 재확인 필수.
        // (Refund 가 적은 빈도라 이중 fetch 가 큰 비용 아님.)
        // 후보 list 안에 같은 키가 두 번 들어올 일은 unique index 로 막음.
        val matches = refunds.findStaleRequested(clock.instant(), 1000)
        val refund = matches.firstOrNull { idempotencyKey == it.idempotencyKey } ?: return false
        if (refund.status != RefundStatus.REQUESTED) return false

        when (lookup.status) {
            PgClient.LookupStatus.APPROVED -> {
                val approved = refund.approve(lookup.pgReferenceId!!, clock)
                val completed = refund.complete(clock)
                refunds.save(refund)
                events.publish(approved)
                events.publish(completed)
                audit.log(
                    AuditActor.system("pg-reconciler"),
                    AuditAction.REFUND_RECONCILED,
                    "Refund", refund.id.toString(),
                    null,
                    AuditPayloads.`object`()
                        .put("resolution", "APPROVED")
                        .put("idempotencyKey", idempotencyKey)
                        .put("pgRefundId", lookup.pgReferenceId)
                        .build(),
                    "phase 3 retry via PG lookup",
                )
                log.info("reconciled refund APPROVED id={} key={}", refund.id, idempotencyKey)
            }
            PgClient.LookupStatus.REJECTED, PgClient.LookupStatus.NOT_FOUND -> {
                val msg = lookup.errorMessage ?: "reconciled by lookup"
                val failed = refund.fail(msg, clock)
                refunds.save(refund)
                events.publish(failed)
                audit.log(
                    AuditActor.system("pg-reconciler"),
                    AuditAction.REFUND_RECONCILED,
                    "Refund", refund.id.toString(),
                    null,
                    AuditPayloads.`object`()
                        .put("resolution", lookup.status)
                        .put("idempotencyKey", idempotencyKey)
                        .build(),
                    "phase 3 retry via PG lookup",
                )
                log.info("reconciled refund {} id={} key={}", lookup.status, refund.id, idempotencyKey)
            }
            PgClient.LookupStatus.IN_PROGRESS -> {
                return false
            }
        }
        return true
    }

    companion object {
        private val log = LoggerFactory.getLogger(ReconcilePgFailuresService::class.java)
    }
}
