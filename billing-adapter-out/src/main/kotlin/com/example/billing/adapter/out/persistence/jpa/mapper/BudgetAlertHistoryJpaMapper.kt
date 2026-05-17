package com.example.billing.adapter.out.persistence.jpa.mapper

import com.example.billing.adapter.out.persistence.jpa.entity.BudgetAlertHistoryJpaEntity
import com.example.billing.domain.budget.BudgetAlertHistoryEntry
import com.example.billing.domain.budget.BudgetAlertRuleId
import com.example.billing.domain.settlement.BillingPeriod
import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.shared.Money
import java.time.YearMonth
import java.util.Currency

object BudgetAlertHistoryJpaMapper {

    @JvmStatic
    fun toEntity(h: BudgetAlertHistoryEntry): BudgetAlertHistoryJpaEntity = BudgetAlertHistoryJpaEntity(
        h.id,
        h.ruleId.value,
        h.customerId.value,
        h.thresholdAtTrigger.amount,
        h.projectedCostAtTrigger.amount,
        h.thresholdAtTrigger.currency.currencyCode,
        h.overshootRatio,
        h.period.toKey(),
        h.periodProgressRatioAtTrigger,
        h.occurredAt,
    )

    @JvmStatic
    fun toDomain(e: BudgetAlertHistoryJpaEntity): BudgetAlertHistoryEntry {
        val currency = Currency.getInstance(e.currency)
        return BudgetAlertHistoryEntry(
            e.id!!,
            BudgetAlertRuleId(e.ruleId!!),
            CustomerId.of(e.customerId),
            Money.of(e.thresholdAmountAtTrigger, currency),
            Money.of(e.projectedCostAtTrigger, currency),
            e.overshootRatio,
            BillingPeriod.of(YearMonth.parse(e.periodYearMonth)),
            e.periodProgressRatioAtTrigger,
            e.occurredAt,
        )
    }
}
