package com.example.billing.domain.payment

import com.example.billing.domain.order.OrderId
import com.example.billing.domain.shared.DomainEvent
import com.example.billing.domain.shared.Money
import java.time.Instant

/**
 * Payment 도메인 이벤트 sealed 트리.
 *
 * Java 호출자는 `event.aggregateId()` / `event.amount()` / `event.pgTransactionId()` 등
 * record-style accessor 그대로 호출. data class 의 component 이름 충돌 회피로 `private val
 * occurredAtInstant` + `override fun occurredAt() = it` 패턴 (wallet / credit / refund 와
 * 동일).
 */
sealed interface PaymentEvents : DomainEvent {

    data class PaymentApproved(
        @get:JvmName("paymentId") val paymentId: PaymentId,
        @get:JvmName("orderId") val orderId: OrderId,
        @get:JvmName("amount") val amount: Money,
        @get:JvmName("pgTransactionId") val pgTransactionId: String,
        private val occurredAtInstant: Instant,
    ) : PaymentEvents {
        override fun aggregateId(): String = paymentId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class PaymentRejected(
        @get:JvmName("paymentId") val paymentId: PaymentId,
        @get:JvmName("orderId") val orderId: OrderId,
        @get:JvmName("amount") val amount: Money,
        @get:JvmName("errorCode") val errorCode: String,
        @get:JvmName("errorMessage") val errorMessage: String,
        private val occurredAtInstant: Instant,
    ) : PaymentEvents {
        override fun aggregateId(): String = paymentId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }
}
