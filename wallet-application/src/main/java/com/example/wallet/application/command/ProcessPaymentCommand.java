package com.example.wallet.application.command;

import com.example.wallet.domain.order.OrderId;
import com.example.wallet.domain.payment.PaymentMethod;

public record ProcessPaymentCommand(
        String idempotencyKey,
        OrderId orderId,
        PaymentMethod method
) {}
