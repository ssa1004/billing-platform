package com.example.wallet.adapter.web.dto

import com.example.wallet.application.command.RefundCommand
import com.example.wallet.domain.payment.PaymentId
import com.example.wallet.domain.refund.Refund
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant

data class RefundRequest(
    @field:NotNull val paymentId: String,
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
) {
    companion object {
        fun from(r: Refund): RefundResponse = RefundResponse(
            id = r.id().toString(),
            paymentId = r.paymentId().toString(),
            amount = r.amount().amount(),
            currency = r.amount().currency().currencyCode,
            reason = r.reason(),
            status = r.status().name,
            pgRefundId = r.pgRefundId(),
            requestedAt = r.requestedAt(),
            completedAt = r.completedAt(),
        )
    }
}
