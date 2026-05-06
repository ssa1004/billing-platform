package com.example.billing.application.port.in;

import com.example.billing.application.command.CreateBudgetAlertRuleCommand;
import com.example.billing.domain.budget.BudgetAlertRule;

public interface CreateBudgetAlertRuleUseCase {
    BudgetAlertRule create(CreateBudgetAlertRuleCommand command);
}
