package com.example.billing.application.service

import com.example.billing.application.port.`in`.BudgetAlertHistoryQueryUseCase
import com.example.billing.application.port.out.BudgetAlertHistoryRepository
import com.example.billing.domain.budget.BudgetAlertHistoryEntry
import com.example.billing.domain.budget.BudgetAlertRuleId
import com.example.billing.domain.shared.CustomerId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
open class BudgetAlertHistoryQueryService(
    private val history: BudgetAlertHistoryRepository,
) : BudgetAlertHistoryQueryUseCase {

    override fun findByRule(ruleId: BudgetAlertRuleId, limit: Int): List<BudgetAlertHistoryEntry> =
        history.findByRule(ruleId, limit)

    override fun findByCustomer(customerId: CustomerId, limit: Int): List<BudgetAlertHistoryEntry> =
        history.findByCustomer(customerId, limit)
}
