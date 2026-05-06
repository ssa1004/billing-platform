package com.example.billing.application.command;

import com.example.billing.domain.shared.Money;

import java.time.Duration;

/** {@code cooldown} null 이면 도메인 default (24h) 사용. */
public record CreateBudgetAlertRuleCommand(
        String idempotencyKey,
        String customerId,
        Money threshold,
        Duration cooldown
) {}
