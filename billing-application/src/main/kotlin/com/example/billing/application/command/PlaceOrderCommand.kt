package com.example.billing.application.command

import com.example.billing.domain.order.OrderItem
import com.example.billing.domain.shared.Money
import java.math.BigDecimal
import java.util.Currency

@JvmRecord
data class PlaceOrderCommand(
    val idempotencyKey: String,
    val buyerId: String,
    val currency: Currency,
    val lines: List<OrderLine>,
) {

    fun toOrderItems(): List<OrderItem> =
        lines.map { OrderItem(it.sku, it.quantity, Money.of(it.unitPrice, currency)) }

    @JvmRecord
    data class OrderLine(val sku: String, val quantity: Int, val unitPrice: BigDecimal)
}
