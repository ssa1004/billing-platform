package com.example.billing.application.command

import com.example.billing.domain.payment.PaymentId

@JvmRecord
data class RefundCommand(
    val idempotencyKey: String,
    val paymentId: PaymentId,
    val reason: String,
)
