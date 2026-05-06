package com.example.billing.adapter.out.persistence.jpa.repository;

import com.example.billing.adapter.out.persistence.jpa.entity.BudgetAlertHistoryJpaEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataBudgetAlertHistoryRepository extends JpaRepository<BudgetAlertHistoryJpaEntity, UUID> {

    List<BudgetAlertHistoryJpaEntity> findByRuleIdOrderByOccurredAtDesc(UUID ruleId, Pageable pageable);

    List<BudgetAlertHistoryJpaEntity> findByCustomerIdOrderByOccurredAtDesc(
            String customerId, Pageable pageable);
}
