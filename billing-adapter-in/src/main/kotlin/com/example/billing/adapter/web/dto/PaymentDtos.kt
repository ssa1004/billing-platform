package com.example.billing.adapter.web.dto

import com.example.billing.application.command.ProcessPaymentCommand
import com.example.billing.domain.order.OrderId
import com.example.billing.domain.payment.Payment
import com.example.billing.domain.payment.PaymentMethod
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant

data class ProcessPaymentRequest(
    @field:NotBlank @field:Size(max = 64) val orderId: String,
    @field:NotNull val method: PaymentMethod,
) {
    fun toCommand(idempotencyKey: String): ProcessPaymentCommand =
        ProcessPaymentCommand(idempotencyKey, OrderId.of(orderId), method)
}

data class PaymentResponse(
    val id: String,
    val orderId: String,
    val amount: BigDecimal,
    val currency: String,
    val method: String,
    val status: String,
    val pgTransactionId: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

fun Payment.toResponse(): PaymentResponse = PaymentResponse(
    id = id.toString(),
    orderId = orderId.toString(),
    amount = amount.amount,
    currency = amount.currency.currencyCode,
    method = method.name,
    status = status.name,
    pgTransactionId = pgTransactionId,
    errorCode = errorCode,
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
