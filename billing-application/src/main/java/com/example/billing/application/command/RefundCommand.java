package com.example.billing.application.command;

import com.example.billing.domain.payment.PaymentId;

public record RefundCommand(
        String idempotencyKey,
        PaymentId paymentId,
        String reason
) {}
