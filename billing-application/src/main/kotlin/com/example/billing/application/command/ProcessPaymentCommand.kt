package com.example.billing.application.command

import com.example.billing.domain.order.OrderId
import com.example.billing.domain.payment.PaymentMethod

@JvmRecord
data class ProcessPaymentCommand(
    val idempotencyKey: String,
    val orderId: OrderId,
    val method: PaymentMethod,
)
