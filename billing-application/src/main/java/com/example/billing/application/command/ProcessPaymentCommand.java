package com.example.billing.application.command;

import com.example.billing.domain.order.OrderId;
import com.example.billing.domain.payment.PaymentMethod;

public record ProcessPaymentCommand(
        String idempotencyKey,
        OrderId orderId,
        PaymentMethod method
) {}
