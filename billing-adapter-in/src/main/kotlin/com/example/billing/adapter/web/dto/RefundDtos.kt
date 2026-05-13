package com.example.billing.adapter.web.dto

import com.example.billing.application.command.RefundCommand
import com.example.billing.domain.payment.PaymentId
import com.example.billing.domain.refund.Refund
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant

data class RefundRequest(
    @field:NotBlank @field:Size(max = 64) val paymentId: String,
    @field:Size(max = 512) val reason: String?,
) {
    fun toCommand(idempotencyKey: String): RefundCommand =
        RefundCommand(idempotencyKey, PaymentId.of(paymentId), reason ?: "")
}

data class RefundResponse(
    val id: String,
    val paymentId: String,
    val amount: BigDecimal,
    val currency: String,
    val reason: String?,
    val status: String,
    val pgRefundId: String?,
    val requestedAt: Instant,
    val completedAt: Instant?,
)

fun Refund.toResponse(): RefundResponse = RefundResponse(
    id = id.toString(),
    paymentId = paymentId.toString(),
    amount = amount.amount(),
    currency = amount.currency().currencyCode,
    reason = reason,
    status = status.name,
    pgRefundId = pgRefundId,
    requestedAt = requestedAt,
    completedAt = completedAt,
)
