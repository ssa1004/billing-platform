package com.example.billing.adapter.out.persistence.jpa;

import com.example.billing.adapter.out.persistence.jpa.mapper.BudgetAlertHistoryJpaMapper;
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataBudgetAlertHistoryRepository;
import com.example.billing.application.port.out.BudgetAlertHistoryRepository;
import com.example.billing.domain.budget.BudgetAlertHistoryEntry;
import com.example.billing.domain.budget.BudgetAlertRuleId;
import com.example.billing.domain.shared.CustomerId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class JpaBudgetAlertHistoryRepositoryAdapter implements BudgetAlertHistoryRepository {

    private final SpringDataBudgetAlertHistoryRepository jpa;

    @Override
    public void save(BudgetAlertHistoryEntry entry) {
        jpa.save(BudgetAlertHistoryJpaMapper.toEntity(entry));
    }

    @Override
    public List<BudgetAlertHistoryEntry> findByRule(BudgetAlertRuleId ruleId, int limit) {
        return jpa.findByRuleIdOrderByOccurredAtDesc(ruleId.value(), PageRequest.of(0, limit))
                .stream()
                .map(BudgetAlertHistoryJpaMapper::toDomain)
                .toList();
    }

    @Override
    public List<BudgetAlertHistoryEntry> findByCustomer(CustomerId customerId, int limit) {
        return jpa.findByCustomerIdOrderByOccurredAtDesc(customerId.value(), PageRequest.of(0, limit))
                .stream()
                .map(BudgetAlertHistoryJpaMapper::toDomain)
                .toList();
    }
}
