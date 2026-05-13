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
    // OWASP API4 — Unrestricted Resource Consumption: items 가 무한 길이면 한 요청이 DB / 메모리
    // / 후속 결제 흐름을 통째로 흔들 수 있다. 운영 상한 100 — 단일 결제 라인 100개면 충분.
    @field:NotEmpty @field:Valid @field:Size(max = 100) val items: List<OrderItemRequest>,
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
)

data class OrderItemResponse(val sku: String, val quantity: Int, val unitPrice: BigDecimal)

fun Order.toResponse(): OrderResponse = OrderResponse(
    id = id().toString(),
    buyerId = buyerId(),
    totalAmount = totalAmount().amount(),
    currency = currency().currencyCode,
    status = status().name,
    paymentId = paymentId(),
    refundId = refundId(),
    items = items().map { OrderItemResponse(it.sku(), it.quantity(), it.unitPrice().amount()) },
    createdAt = createdAt(),
    updatedAt = updatedAt(),
)
