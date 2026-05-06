package com.example.billing.application.command;

import com.example.billing.domain.credit.CreditType;
import com.example.billing.domain.shared.Money;

import java.time.Instant;

public record GrantCreditCommand(
        String idempotencyKey,
        String customerId,
        CreditType type,
        Money amount,
        Instant validFrom,
        Instant validUntil,   // nullable
        String reason
) {}
