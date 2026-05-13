package com.example.billing.domain.refund

import com.example.billing.domain.payment.PaymentId
import com.example.billing.domain.shared.DomainEvent
import com.example.billing.domain.shared.Money
import java.time.Clock
import java.time.Instant

/**
 * Refund 애그리거트. 환불 요청 → PG 환불 호출 → Wallet 환원 흐름의 상태 보존.
 *
 * **idempotencyKey**: phase 1 (트랜잭션 안에서 REQUESTED Refund 영속화) 시점에 점유한
 * 키를 그대로 보관. PG-reconciler 가 PENDING/REQUESTED 상태로 stuck 된 Refund 를 발견했을 때
 * 같은 키로 PG lookup 해서 실제 결과를 다시 끌어올 때 사용.
 *
 * record-style accessor (id(), paymentId(), status() 등) 는 `@get:JvmName` 으로 Java/Kotlin
 * 양쪽 호출자 호환 유지.
 */
class Refund private constructor(
    @get:JvmName("id") val id: RefundId,
    @get:JvmName("paymentId") val paymentId: PaymentId,
    @get:JvmName("amount") val amount: Money,
    @get:JvmName("reason") val reason: String?,
    @get:JvmName("idempotencyKey") val idempotencyKey: String,
    status: RefundStatus,
    pgRefundId: String?,
    @get:JvmName("requestedAt") val requestedAt: Instant,
    completedAt: Instant?,
    @get:JvmName("version") val version: Long,
) {

    @get:JvmName("status")
    var status: RefundStatus = status
        private set

    @get:JvmName("pgRefundId")
    var pgRefundId: String? = pgRefundId
        private set

    @get:JvmName("completedAt")
    var completedAt: Instant? = completedAt
        private set

    fun approve(pgRefundId: String, clock: Clock): RefundApproved {
        check(status == RefundStatus.REQUESTED) {
            "refund must be REQUESTED to approve, was $status"
        }
        this.status = RefundStatus.APPROVED
        this.pgRefundId = pgRefundId
        return RefundApproved(id, paymentId, amount, pgRefundId, clock.instant())
    }

    fun complete(clock: Clock): RefundCompleted {
        check(status == RefundStatus.APPROVED) {
            "refund must be APPROVED to complete, was $status"
        }
        this.status = RefundStatus.COMPLETED
        this.completedAt = clock.instant()
        return RefundCompleted(id, paymentId, amount, completedAt!!)
    }

    fun fail(reason: String, clock: Clock): RefundFailed {
        check(!status.isTerminal()) { "refund already terminal: $status" }
        this.status = RefundStatus.FAILED
        this.completedAt = clock.instant()
        return RefundFailed(id, paymentId, reason, completedAt!!)
    }

    companion object {
        @JvmStatic
        fun request(
            paymentId: PaymentId,
            amount: Money,
            reason: String?,
            idempotencyKey: String,
            clock: Clock,
        ): Refund {
            require(amount.isPositive) { "amount must be positive" }
            require(idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
            return Refund(
                id = RefundId.newId(),
                paymentId = paymentId,
                amount = amount,
                reason = reason,
                idempotencyKey = idempotencyKey,
                status = RefundStatus.REQUESTED,
                pgRefundId = null,
                requestedAt = clock.instant(),
                completedAt = null,
                version = 0L,
            )
        }

        /** 영속 계층에서 복원. */
        @JvmStatic
        fun restore(
            id: RefundId,
            paymentId: PaymentId,
            amount: Money,
            reason: String?,
            idempotencyKey: String,
            status: RefundStatus,
            pgRefundId: String?,
            requestedAt: Instant,
            completedAt: Instant?,
            version: Long,
        ): Refund = Refund(
            id = id,
            paymentId = paymentId,
            amount = amount,
            reason = reason,
            idempotencyKey = idempotencyKey,
            status = status,
            pgRefundId = pgRefundId,
            requestedAt = requestedAt,
            completedAt = completedAt,
            version = version,
        )
    }

    data class RefundApproved(
        @get:JvmName("refundId") val refundId: RefundId,
        @get:JvmName("paymentId") val paymentId: PaymentId,
        @get:JvmName("amount") val amount: Money,
        @get:JvmName("pgRefundId") val pgRefundId: String,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = refundId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class RefundCompleted(
        @get:JvmName("refundId") val refundId: RefundId,
        @get:JvmName("paymentId") val paymentId: PaymentId,
        @get:JvmName("amount") val amount: Money,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = refundId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class RefundFailed(
        @get:JvmName("refundId") val refundId: RefundId,
        @get:JvmName("paymentId") val paymentId: PaymentId,
        @get:JvmName("reason") val reason: String,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = refundId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }
}
