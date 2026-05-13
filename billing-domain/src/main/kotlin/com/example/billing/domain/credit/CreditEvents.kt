package com.example.billing.domain.credit

import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.shared.DomainEvent
import com.example.billing.domain.shared.Money
import com.example.billing.domain.shared.Reference
import java.time.Instant

/**
 * Credit 도메인 이벤트.
 *
 * 모든 잔액 변경 메서드는 [Credit] 가 직접 상태를 바꾸고 해당 이벤트를 반환한다. 호출 측
 * (application service) 가 Outbox 에 기록 → Kafka publish.
 *
 * record-style accessor (`creditId()`, `customerId()`, `occurredAt()` 등) 는 `@get:JvmName`
 * 으로 Java/Kotlin 양쪽 호출자에서 그대로 호출 가능 — 기존 Java 호출자 (`event.consumedAmount()`,
 * `event.aggregateId()`) 무변경.
 */
object CreditEvents {

    data class CreditGranted(
        @get:JvmName("creditId") val creditId: CreditId,
        @get:JvmName("customerId") val customerId: CustomerId,
        @get:JvmName("type") val type: CreditType,
        @get:JvmName("grantedAmount") val grantedAmount: Money,
        @get:JvmName("validFrom") val validFrom: Instant,
        /** nullable = 만료 없음 */
        @get:JvmName("validUntil") val validUntil: Instant?,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = creditId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class CreditConsumed(
        @get:JvmName("creditId") val creditId: CreditId,
        @get:JvmName("customerId") val customerId: CustomerId,
        @get:JvmName("consumedAmount") val consumedAmount: Money,
        @get:JvmName("remainingBalance") val remainingBalance: Money,
        /** 보통 InvoiceId */
        @get:JvmName("reference") val reference: Reference,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = creditId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class CreditExhausted(
        @get:JvmName("creditId") val creditId: CreditId,
        @get:JvmName("customerId") val customerId: CustomerId,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = creditId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class CreditExpired(
        @get:JvmName("creditId") val creditId: CreditId,
        @get:JvmName("customerId") val customerId: CustomerId,
        /** 만료 시점에 남아 있던 잔액 */
        @get:JvmName("forfeitedBalance") val forfeitedBalance: Money,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = creditId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class CreditRevoked(
        @get:JvmName("creditId") val creditId: CreditId,
        @get:JvmName("customerId") val customerId: CustomerId,
        @get:JvmName("revokedBalance") val revokedBalance: Money,
        @get:JvmName("reason") val reason: String,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = creditId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }
}
