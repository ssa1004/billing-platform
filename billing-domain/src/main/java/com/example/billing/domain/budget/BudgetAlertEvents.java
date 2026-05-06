package com.example.billing.domain.budget;

import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.DomainEvent;
import com.example.billing.domain.shared.Money;

import java.time.Instant;

/**
 * BudgetAlertRule 도메인 이벤트.
 *
 * <p>{@link Triggered} 가 가장 중요 — 컨슈머가 customer 알림 채널 (email / Slack / webhook) 로 push.</p>
 */
public final class BudgetAlertEvents {

    private BudgetAlertEvents() {}

    public record Created(
            BudgetAlertRuleId ruleId,
            CustomerId customerId,
            Money threshold,
            Instant occurredAt
    ) implements DomainEvent {
        @Override public String aggregateId() { return ruleId.toString(); }
    }

    public record Triggered(
            BudgetAlertRuleId ruleId,
            CustomerId customerId,
            Money threshold,
            Money projectedCost,
            double overshootRatio,    // projectedCost / threshold (1.0 = 정확히 임계, 1.5 = 50% 초과)
            Instant occurredAt
    ) implements DomainEvent {
        @Override public String aggregateId() { return ruleId.toString(); }
    }

    public record Paused(
            BudgetAlertRuleId ruleId,
            CustomerId customerId,
            Instant occurredAt
    ) implements DomainEvent {
        @Override public String aggregateId() { return ruleId.toString(); }
    }

    public record Resumed(
            BudgetAlertRuleId ruleId,
            CustomerId customerId,
            Instant occurredAt
    ) implements DomainEvent {
        @Override public String aggregateId() { return ruleId.toString(); }
    }
}
