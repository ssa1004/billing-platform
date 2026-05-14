package com.example.billing.domain.order

import com.example.billing.domain.shared.DomainEvent
import com.example.billing.domain.shared.Money
import java.time.Instant

/**
 * Order 도메인 이벤트 sealed 트리.
 *
 * Java 호출자는 `event.aggregateId()` / `event.totalAmount()` / `event.paymentId()` 등
 * record-style accessor 그대로 호출. data class 의 component 이름 충돌 회피로 `private val
 * occurredAtInstant` + `override fun occurredAt() = it` 패턴 (wallet / payment / credit 과
 * 동일).
 */
sealed interface OrderEvents : DomainEvent {

    data class OrderPlaced(
        @get:JvmName("orderId") val orderId: OrderId,
        @get:JvmName("buyerId") val buyerId: String,
        @get:JvmName("totalAmount") val totalAmount: Money,
        private val occurredAtInstant: Instant,
    ) : OrderEvents {
        override fun aggregateId(): String = orderId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class OrderPaid(
        @get:JvmName("orderId") val orderId: OrderId,
        @get:JvmName("paymentId") val paymentId: String,
        @get:JvmName("totalAmount") val totalAmount: Money,
        private val occurredAtInstant: Instant,
    ) : OrderEvents {
        override fun aggregateId(): String = orderId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class OrderCancelled(
        @get:JvmName("orderId") val orderId: OrderId,
        @get:JvmName("reason") val reason: String,
        private val occurredAtInstant: Instant,
    ) : OrderEvents {
        override fun aggregateId(): String = orderId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class OrderRefunded(
        @get:JvmName("orderId") val orderId: OrderId,
        @get:JvmName("refundId") val refundId: String,
        @get:JvmName("totalAmount") val totalAmount: Money,
        private val occurredAtInstant: Instant,
    ) : OrderEvents {
        override fun aggregateId(): String = orderId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class OrderFailed(
        @get:JvmName("orderId") val orderId: OrderId,
        @get:JvmName("reason") val reason: String,
        private val occurredAtInstant: Instant,
    ) : OrderEvents {
        override fun aggregateId(): String = orderId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }
}
