package com.example.billing.adapter.web.dto

import com.example.billing.application.command.PlaceOrderCommand
import com.example.billing.domain.order.Order
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

data class PlaceOrderRequest(
    @field:NotBlank @field:Size(min = 3, max = 3) val currency: String,
    @field:NotEmpty @field:Valid val items: List<OrderItemRequest>,
) {
    fun toCommand(idempotencyKey: String, buyerId: String): PlaceOrderCommand =
        PlaceOrderCommand(
            idempotencyKey,
            buyerId,
            Currency.getInstance(currency),
            items.map { PlaceOrderCommand.OrderLine(it.sku, it.quantity, it.unitPrice) },
        )
}

data class OrderItemRequest(
    @field:NotBlank val sku: String,
    @field:Min(1) val quantity: Int,
    @field:DecimalMin("0.01") val unitPrice: BigDecimal,
)

data class OrderResponse(
    val id: String,
    val buyerId: String,
    val totalAmount: BigDecimal,
    val currency: String,
    val status: String,
    val paymentId: String?,
    val refundId: String?,
    val items: List<OrderItemResponse>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(o: Order): OrderResponse = OrderResponse(
            id = o.id().toString(),
            buyerId = o.buyerId(),
            totalAmount = o.totalAmount().amount(),
            currency = o.currency().currencyCode,
            status = o.status().name,
            paymentId = o.paymentId(),
            refundId = o.refundId(),
            items = o.items().map { OrderItemResponse(it.sku(), it.quantity(), it.unitPrice().amount()) },
            createdAt = o.createdAt(),
            updatedAt = o.updatedAt(),
        )
    }
}

data class OrderItemResponse(val sku: String, val quantity: Int, val unitPrice: BigDecimal)
