package com.example.billing.application.service;

import com.example.billing.application.port.in.BudgetAlertHistoryQueryUseCase;
import com.example.billing.application.port.out.BudgetAlertHistoryRepository;
import com.example.billing.domain.budget.BudgetAlertHistoryEntry;
import com.example.billing.domain.budget.BudgetAlertRuleId;
import com.example.billing.domain.shared.CustomerId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BudgetAlertHistoryQueryService implements BudgetAlertHistoryQueryUseCase {

    private final BudgetAlertHistoryRepository history;

    @Override
    public List<BudgetAlertHistoryEntry> findByRule(BudgetAlertRuleId ruleId, int limit) {
        return history.findByRule(ruleId, limit);
    }

    @Override
    public List<BudgetAlertHistoryEntry> findByCustomer(CustomerId customerId, int limit) {
        return history.findByCustomer(customerId, limit);
    }
}
