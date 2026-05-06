package com.example.billing.adapter.out.persistence.jpa.mapper;

import com.example.billing.adapter.out.persistence.jpa.entity.BudgetAlertRuleJpaEntity;
import com.example.billing.domain.budget.BudgetAlertRule;
import com.example.billing.domain.budget.BudgetAlertRuleId;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;

import java.time.Duration;
import java.util.Currency;

public final class BudgetAlertRuleJpaMapper {

    private BudgetAlertRuleJpaMapper() {}

    public static BudgetAlertRuleJpaEntity toEntity(BudgetAlertRule r) {
        return new BudgetAlertRuleJpaEntity(
                r.id().value(),
                r.customerId().value(),
                r.threshold().amount(),
                r.threshold().currency().getCurrencyCode(),
                r.cooldown().getSeconds(),
                r.status(),
                r.lastEvaluatedAt(),
                r.lastTriggeredAt(),
                r.createdAt(),
                r.updatedAt(),
                r.version()
        );
    }

    public static BudgetAlertRule toDomain(BudgetAlertRuleJpaEntity e) {
        Currency currency = Currency.getInstance(e.getCurrency());
        return BudgetAlertRule.restore(
                new BudgetAlertRuleId(e.getId()),
                CustomerId.of(e.getCustomerId()),
                Money.of(e.getThresholdAmount(), currency),
                Duration.ofSeconds(e.getCooldownSeconds()),
                e.getStatus(),
                e.getLastEvaluatedAt(),
                e.getLastTriggeredAt(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getVersion()
        );
    }
}
