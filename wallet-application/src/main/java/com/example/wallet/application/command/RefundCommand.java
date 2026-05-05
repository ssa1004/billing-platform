package com.example.wallet.application.command;

import com.example.wallet.domain.payment.PaymentId;

public record RefundCommand(
        String idempotencyKey,
        PaymentId paymentId,
        String reason
) {}
