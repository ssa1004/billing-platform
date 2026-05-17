package com.example.billing.adapter.out.persistence.jpa.mapper

import com.example.billing.adapter.out.persistence.jpa.entity.OrderItemJpaEntity
import com.example.billing.adapter.out.persistence.jpa.entity.OrderJpaEntity
import com.example.billing.domain.order.Order
import com.example.billing.domain.order.OrderId
import com.example.billing.domain.order.OrderItem
import com.example.billing.domain.order.OrderStatus
import com.example.billing.domain.shared.Money
import java.util.Currency
import java.util.UUID

object OrderJpaMapper {

    @JvmStatic
    fun toEntity(o: Order): OrderJpaEntity {
        val e = OrderJpaEntity()
        e.id = o.id.value
        e.buyerId = o.buyerId
        e.totalAmount = o.totalAmount.amount
        e.currency = o.currency.currencyCode
        e.status = o.status.name
        e.paymentId = o.paymentId?.let { UUID.fromString(it) }
        e.refundId = o.refundId?.let { UUID.fromString(it) }
        e.createdAt = o.createdAt
        e.updatedAt = o.updatedAt
        e.version = o.version

        val items = o.items.map { item ->
            val ie = OrderItemJpaEntity()
            ie.orderId = o.id.value
            ie.sku = item.sku
            ie.quantity = item.quantity
            ie.unitPrice = item.unitPrice.amount
            ie
        }
        e.items.clear()
        e.items.addAll(items)
        return e
    }

    @JvmStatic
    fun toDomain(e: OrderJpaEntity): Order {
        val currency = Currency.getInstance(e.currency)
        val items = e.items.map { ie ->
            OrderItem(ie.sku, ie.quantity, Money.of(ie.unitPrice, currency))
        }
        return Order.restore(
            OrderId(e.id!!),
            e.buyerId,
            items,
            Money.of(e.totalAmount, currency),
            currency,
            OrderStatus.valueOf(e.status),
            e.paymentId?.toString(),
            e.refundId?.toString(),
            e.createdAt,
            e.updatedAt,
            e.version,
        )
    }
}
