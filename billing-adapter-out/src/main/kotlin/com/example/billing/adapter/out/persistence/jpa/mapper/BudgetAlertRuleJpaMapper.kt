package com.example.billing.adapter.out.persistence.jpa.mapper

import com.example.billing.adapter.out.persistence.jpa.entity.BudgetAlertRuleJpaEntity
import com.example.billing.domain.budget.BudgetAlertRule
import com.example.billing.domain.budget.BudgetAlertRuleId
import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.shared.Money
import java.time.Duration
import java.util.Currency

object BudgetAlertRuleJpaMapper {

    @JvmStatic
    fun toEntity(r: BudgetAlertRule): BudgetAlertRuleJpaEntity = BudgetAlertRuleJpaEntity(
        r.id.value,
        r.customerId.value,
        r.threshold.amount,
        r.threshold.currency.currencyCode,
        r.cooldown.seconds,
        r.status,
        r.lastEvaluatedAt,
        r.lastTriggeredAt,
        r.createdAt,
        r.updatedAt,
        r.version,
    )

    @JvmStatic
    fun toDomain(e: BudgetAlertRuleJpaEntity): BudgetAlertRule {
        val currency = Currency.getInstance(e.currency)
        return BudgetAlertRule.restore(
            BudgetAlertRuleId(e.id!!),
            CustomerId.of(e.customerId),
            Money.of(e.thresholdAmount, currency),
            Duration.ofSeconds(e.cooldownSeconds),
            e.status,
            e.lastEvaluatedAt,
            e.lastTriggeredAt,
            e.createdAt,
            e.updatedAt,
            e.version,
        )
    }
}
