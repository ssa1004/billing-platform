package com.example.billing.application.exception;

import com.example.billing.domain.budget.BudgetAlertRuleId;

public class BudgetAlertRuleNotFoundException extends RuntimeException {
    public BudgetAlertRuleNotFoundException(BudgetAlertRuleId id) {
        super("budget alert rule not found: " + id);
    }
}
