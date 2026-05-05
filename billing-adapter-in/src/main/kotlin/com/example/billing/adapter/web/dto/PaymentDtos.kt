package com.example.billing.adapter.web.dto

import com.example.billing.application.command.ProcessPaymentCommand
import com.example.billing.domain.order.OrderId
import com.example.billing.domain.payment.Payment
import com.example.billing.domain.payment.PaymentMethod
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.Instant

data class ProcessPaymentRequest(
    @field:NotNull val orderId: String,
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
) {
    companion object {
        fun from(p: Payment): PaymentResponse = PaymentResponse(
            id = p.id().toString(),
            orderId = p.orderId().toString(),
            amount = p.amount().amount(),
            currency = p.amount().currency().currencyCode,
            method = p.method().name,
            status = p.status().name,
            pgTransactionId = p.pgTransactionId(),
            errorCode = p.errorCode(),
            errorMessage = p.errorMessage(),
            createdAt = p.createdAt(),
            updatedAt = p.updatedAt(),
        )
    }
}
