package com.example.billing.application.port.`in`

import com.example.billing.domain.budget.BudgetAlertHistoryEntry
import com.example.billing.domain.budget.BudgetAlertRuleId
import com.example.billing.domain.shared.CustomerId

interface BudgetAlertHistoryQueryUseCase {

    /** 특정 rule 의 트리거 timeline (최근 → 과거). */
    fun findByRule(ruleId: BudgetAlertRuleId, limit: Int): List<BudgetAlertHistoryEntry>

    /** customer 의 모든 rule 통합 timeline. */
    fun findByCustomer(customerId: CustomerId, limit: Int): List<BudgetAlertHistoryEntry>
}
