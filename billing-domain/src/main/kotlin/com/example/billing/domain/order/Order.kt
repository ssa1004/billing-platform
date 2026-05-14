package com.example.billing.domain.order

import com.example.billing.domain.shared.Money
import java.time.Clock
import java.time.Instant
import java.util.Currency

/**
 * Order 애그리거트 루트.
 *
 * **Invariant**:
 * - items 비어있지 않음, 모든 라인이 동일 통화
 * - totalAmount = sum(item.lineTotal())
 * - 상태 천이는 [OrderStatus.canTransitionTo] 가 허용한 경우만
 *
 * **컬렉션 방어적 복사**: [items] 는 생성자에서 `List.copyOf` 로 불변 복사본을 만들어 보관하고,
 * accessor 는 그 unmodifiable list 를 그대로 반환합니다 — Java 호출자가 받은 리스트를 수정해도
 * 애그리거트 내부 상태가 흔들리지 않습니다 (기존 Java 구현과 동일 의미).
 *
 * record-style accessor (`id()` / `status()` / `items()` 등) 는 `@get:JvmName` 으로
 * Java/Kotlin 양쪽 호출자 호환 유지.
 */
class Order private constructor(
    @get:JvmName("id") val id: OrderId,
    @get:JvmName("buyerId") val buyerId: String,
    items: List<OrderItem>,
    @get:JvmName("totalAmount") val totalAmount: Money,
    @get:JvmName("currency") val currency: Currency,
    status: OrderStatus,
    paymentId: String?,
    refundId: String?,
    @get:JvmName("createdAt") val createdAt: Instant,
    updatedAt: Instant,
    @get:JvmName("version") val version: Long,
) {

    /** 불변 방어적 복사본 — 외부에서 받은 리스트 변경이 애그리거트에 새지 않도록. */
    @get:JvmName("items")
    val items: List<OrderItem> = java.util.List.copyOf(items)

    @get:JvmName("status")
    var status: OrderStatus = status
        private set

    @get:JvmName("paymentId")
    var paymentId: String? = paymentId
        private set

    @get:JvmName("refundId")
    var refundId: String? = refundId
        private set

    @get:JvmName("updatedAt")
    var updatedAt: Instant = updatedAt
        private set

    fun toPlacedEvent(clock: Clock): OrderEvents.OrderPlaced =
        OrderEvents.OrderPlaced(id, buyerId, totalAmount, clock.instant())

    fun markPaid(paymentId: String, clock: Clock): OrderEvents.OrderPaid {
        transition(OrderStatus.PAID)
        this.paymentId = paymentId
        this.updatedAt = clock.instant()
        return OrderEvents.OrderPaid(id, paymentId, totalAmount, updatedAt)
    }

    fun cancel(reason: String, clock: Clock): OrderEvents.OrderCancelled {
        transition(OrderStatus.CANCELLED)
        this.updatedAt = clock.instant()
        return OrderEvents.OrderCancelled(id, reason, updatedAt)
    }

    fun markRefunded(refundId: String, clock: Clock): OrderEvents.OrderRefunded {
        transition(OrderStatus.REFUNDED)
        this.refundId = refundId
        this.updatedAt = clock.instant()
        return OrderEvents.OrderRefunded(id, refundId, totalAmount, updatedAt)
    }

    fun markFailed(reason: String, clock: Clock): OrderEvents.OrderFailed {
        transition(OrderStatus.FAILED)
        this.updatedAt = clock.instant()
        return OrderEvents.OrderFailed(id, reason, updatedAt)
    }

    private fun transition(next: OrderStatus) {
        if (!status.canTransitionTo(next)) {
            throw IllegalOrderTransitionException(status, next)
        }
        this.status = next
    }

    companion object {

        @JvmStatic
        fun place(buyerId: String, items: List<OrderItem>, clock: Clock): Order {
            require(buyerId.isNotBlank()) { "buyerId must not be blank" }
            require(items.isNotEmpty()) { "items must not be empty" }

            val currency = items[0].unitPrice.currency
            var total = Money.zero(currency)
            for (item in items) {
                require(item.unitPrice.currency == currency) {
                    "all items must share currency: $currency"
                }
                total = total.add(item.lineTotal())
            }
            val now = clock.instant()
            return Order(
                OrderId.newId(), buyerId, items, total, currency,
                OrderStatus.CREATED, null, null, now, now, 0L,
            )
        }

        @JvmStatic
        fun restore(
            id: OrderId,
            buyerId: String,
            items: List<OrderItem>,
            totalAmount: Money,
            currency: Currency,
            status: OrderStatus,
            paymentId: String?,
            refundId: String?,
            createdAt: Instant,
            updatedAt: Instant,
            version: Long,
        ): Order = Order(
            id, buyerId, items, totalAmount, currency, status,
            paymentId, refundId, createdAt, updatedAt, version,
        )
    }
}
