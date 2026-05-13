package com.example.billing.domain.payment

import com.example.billing.domain.order.OrderId
import com.example.billing.domain.shared.Money
import java.time.Clock
import java.time.Instant

/**
 * Payment 애그리거트.
 *
 * PG 호출의 결과를 보존. [idempotencyKey] 가 unique 하므로 같은 키로 중복 결제 시도 시
 * DB unique constraint 가 보호 (DB-level idempotency). Redis NX 와 함께 두 단계 방어
 * (ADR-0006).
 *
 * **상태 라이프사이클**: PENDING → (APPROVED | FAILED) — 종착. terminal 상태로 한 번 들어가면
 * 다시 못 바뀜 (`approve` / `reject` 가 PENDING 검사). 같은 결제 ID 에 대한 PG 재시도는 별도
 * Payment 가 아닌 application 측 retry budget 으로 처리.
 *
 * record-style accessor (`id()` / `status()` / `pgTransactionId()` 등) 는 `@get:JvmName`
 * 으로 Java/Kotlin 양쪽 호출자 호환 유지.
 */
class Payment private constructor(
    @get:JvmName("id") val id: PaymentId,
    @get:JvmName("orderId") val orderId: OrderId,
    @get:JvmName("amount") val amount: Money,
    @get:JvmName("method") val method: PaymentMethod,
    @get:JvmName("idempotencyKey") val idempotencyKey: String,
    status: PaymentStatus,
    pgTransactionId: String?,
    errorCode: String?,
    errorMessage: String?,
    @get:JvmName("createdAt") val createdAt: Instant,
    updatedAt: Instant,
    @get:JvmName("version") val version: Long,
) {

    @get:JvmName("status")
    var status: PaymentStatus = status
        private set

    @get:JvmName("pgTransactionId")
    var pgTransactionId: String? = pgTransactionId
        private set

    @get:JvmName("errorCode")
    var errorCode: String? = errorCode
        private set

    @get:JvmName("errorMessage")
    var errorMessage: String? = errorMessage
        private set

    @get:JvmName("updatedAt")
    var updatedAt: Instant = updatedAt
        private set

    fun approve(pgTransactionId: String, clock: Clock): PaymentEvents.PaymentApproved {
        check(status == PaymentStatus.PENDING) {
            "payment must be PENDING to approve, was $status"
        }
        this.status = PaymentStatus.APPROVED
        this.pgTransactionId = pgTransactionId
        this.updatedAt = clock.instant()
        return PaymentEvents.PaymentApproved(id, orderId, amount, pgTransactionId, updatedAt)
    }

    fun reject(errorCode: String, errorMessage: String, clock: Clock): PaymentEvents.PaymentRejected {
        check(status == PaymentStatus.PENDING) {
            "payment must be PENDING to reject, was $status"
        }
        this.status = PaymentStatus.FAILED
        this.errorCode = errorCode
        this.errorMessage = errorMessage
        this.updatedAt = clock.instant()
        return PaymentEvents.PaymentRejected(id, orderId, amount, errorCode, errorMessage, updatedAt)
    }

    companion object {
        @JvmStatic
        fun initiate(
            orderId: OrderId,
            amount: Money,
            method: PaymentMethod,
            idempotencyKey: String,
            clock: Clock,
        ): Payment {
            require(amount.isPositive) { "amount must be positive" }
            require(idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
            val now = clock.instant()
            return Payment(
                id = PaymentId.newId(),
                orderId = orderId,
                amount = amount,
                method = method,
                idempotencyKey = idempotencyKey,
                status = PaymentStatus.PENDING,
                pgTransactionId = null,
                errorCode = null,
                errorMessage = null,
                createdAt = now,
                updatedAt = now,
                version = 0L,
            )
        }

        /** 영속 계층에서 복원. */
        @JvmStatic
        fun restore(
            id: PaymentId,
            orderId: OrderId,
            amount: Money,
            method: PaymentMethod,
            idempotencyKey: String,
            status: PaymentStatus,
            pgTransactionId: String?,
            errorCode: String?,
            errorMessage: String?,
            createdAt: Instant,
            updatedAt: Instant,
            version: Long,
        ): Payment = Payment(
            id, orderId, amount, method, idempotencyKey, status,
            pgTransactionId, errorCode, errorMessage, createdAt, updatedAt, version,
        )
    }
}
