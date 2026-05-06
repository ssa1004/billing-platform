package com.example.billing.application.port.out;

import com.example.billing.domain.budget.BudgetAlertHistoryEntry;
import com.example.billing.domain.budget.BudgetAlertRuleId;
import com.example.billing.domain.shared.CustomerId;

import java.util.List;

public interface BudgetAlertHistoryRepository {

    void save(BudgetAlertHistoryEntry entry);

    /** rule 별 트리거 timeline (최근 → 과거). */
    List<BudgetAlertHistoryEntry> findByRule(BudgetAlertRuleId ruleId, int limit);

    /** customer 의 모든 rule 통합 timeline. */
    List<BudgetAlertHistoryEntry> findByCustomer(CustomerId customerId, int limit);
}
