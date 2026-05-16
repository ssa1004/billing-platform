package com.example.billing.application.port.`in`

import com.example.billing.application.command.CreateBudgetAlertRuleCommand
import com.example.billing.domain.budget.BudgetAlertRule

interface CreateBudgetAlertRuleUseCase {
    fun create(command: CreateBudgetAlertRuleCommand): BudgetAlertRule
}
