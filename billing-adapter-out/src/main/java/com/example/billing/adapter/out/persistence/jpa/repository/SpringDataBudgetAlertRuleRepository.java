package com.example.billing.adapter.out.persistence.jpa.repository;

import com.example.billing.adapter.out.persistence.jpa.entity.BudgetAlertRuleJpaEntity;
import com.example.billing.domain.budget.BudgetAlertStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SpringDataBudgetAlertRuleRepository extends JpaRepository<BudgetAlertRuleJpaEntity, UUID> {

    List<BudgetAlertRuleJpaEntity> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    /** Evaluate batch — ACTIVE rule 들의 distinct customer 목록. */
    @Query("""
            SELECT DISTINCT r.customerId FROM BudgetAlertRuleJpaEntity r
             WHERE r.status = :status
             ORDER BY r.customerId
            """)
    List<String> findDistinctCustomerIdsByStatus(@Param("status") BudgetAlertStatus status);

    List<BudgetAlertRuleJpaEntity> findByCustomerIdAndStatus(String customerId, BudgetAlertStatus status);
}
