package com.example.billing.adapter.out.persistence.jpa;

import com.example.billing.adapter.out.persistence.jpa.mapper.BudgetAlertRuleJpaMapper;
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataBudgetAlertRuleRepository;
import com.example.billing.application.port.out.BudgetAlertRuleRepository;
import com.example.billing.domain.budget.BudgetAlertRule;
import com.example.billing.domain.budget.BudgetAlertRuleId;
import com.example.billing.domain.budget.BudgetAlertStatus;
import com.example.billing.domain.shared.CustomerId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaBudgetAlertRuleRepositoryAdapter implements BudgetAlertRuleRepository {

    private final SpringDataBudgetAlertRuleRepository jpa;

    @Override
    public void save(BudgetAlertRule rule) {
        jpa.save(BudgetAlertRuleJpaMapper.toEntity(rule));
    }

    @Override
    public Optional<BudgetAlertRule> findById(BudgetAlertRuleId id) {
        return jpa.findById(id.value()).map(BudgetAlertRuleJpaMapper::toDomain);
    }

    @Override
    public List<BudgetAlertRule> findByCustomer(CustomerId customerId) {
        return jpa.findByCustomerIdOrderByCreatedAtDesc(customerId.value()).stream()
                .map(BudgetAlertRuleJpaMapper::toDomain)
                .toList();
    }

    @Override
    public List<CustomerId> findCustomersWithActiveRules() {
        return jpa.findDistinctCustomerIdsByStatus(BudgetAlertStatus.ACTIVE).stream()
                .map(CustomerId::of)
                .toList();
    }

    @Override
    public List<BudgetAlertRule> findActiveByCustomer(CustomerId customerId) {
        return jpa.findByCustomerIdAndStatus(customerId.value(), BudgetAlertStatus.ACTIVE).stream()
                .map(BudgetAlertRuleJpaMapper::toDomain)
                .toList();
    }
}
