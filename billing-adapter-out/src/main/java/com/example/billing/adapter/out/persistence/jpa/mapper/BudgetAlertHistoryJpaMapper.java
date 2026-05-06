package com.example.billing.adapter.out.persistence.jpa.mapper;

import com.example.billing.adapter.out.persistence.jpa.entity.BudgetAlertHistoryJpaEntity;
import com.example.billing.domain.budget.BudgetAlertHistoryEntry;
import com.example.billing.domain.budget.BudgetAlertRuleId;
import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;

import java.time.YearMonth;
import java.util.Currency;

public final class BudgetAlertHistoryJpaMapper {

    private BudgetAlertHistoryJpaMapper() {}

    public static BudgetAlertHistoryJpaEntity toEntity(BudgetAlertHistoryEntry h) {
        return new BudgetAlertHistoryJpaEntity(
                h.id(),
                h.ruleId().value(),
                h.customerId().value(),
                h.thresholdAtTrigger().amount(),
                h.projectedCostAtTrigger().amount(),
                h.thresholdAtTrigger().currency().getCurrencyCode(),
                h.overshootRatio(),
                h.period().toKey(),
                h.periodProgressRatioAtTrigger(),
                h.occurredAt()
        );
    }

    public static BudgetAlertHistoryEntry toDomain(BudgetAlertHistoryJpaEntity e) {
        Currency currency = Currency.getInstance(e.getCurrency());
        return new BudgetAlertHistoryEntry(
                e.getId(),
                new BudgetAlertRuleId(e.getRuleId()),
                CustomerId.of(e.getCustomerId()),
                Money.of(e.getThresholdAmountAtTrigger(), currency),
                Money.of(e.getProjectedCostAtTrigger(), currency),
                e.getOvershootRatio(),
                BillingPeriod.of(YearMonth.parse(e.getPeriodYearMonth())),
                e.getPeriodProgressRatioAtTrigger(),
                e.getOccurredAt()
        );
    }
}
