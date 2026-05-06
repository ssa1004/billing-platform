package com.example.billing.application.port.in;

import com.example.billing.domain.budget.BudgetAlertHistoryEntry;
import com.example.billing.domain.budget.BudgetAlertRuleId;
import com.example.billing.domain.shared.CustomerId;

import java.util.List;

public interface BudgetAlertHistoryQueryUseCase {

    /** 특정 rule 의 트리거 timeline (최근 → 과거). */
    List<BudgetAlertHistoryEntry> findByRule(BudgetAlertRuleId ruleId, int limit);

    /** customer 의 모든 rule 통합 timeline. */
    List<BudgetAlertHistoryEntry> findByCustomer(CustomerId customerId, int limit);
}
