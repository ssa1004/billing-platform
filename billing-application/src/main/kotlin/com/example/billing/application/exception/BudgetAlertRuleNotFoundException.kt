package com.example.billing.application.exception

import com.example.billing.domain.budget.BudgetAlertRuleId

class BudgetAlertRuleNotFoundException(id: BudgetAlertRuleId) :
    RuntimeException("budget alert rule not found: $id")
