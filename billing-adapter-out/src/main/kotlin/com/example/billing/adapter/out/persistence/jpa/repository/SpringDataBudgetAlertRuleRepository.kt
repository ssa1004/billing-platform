package com.example.billing.adapter.out.persistence.jpa.repository

import com.example.billing.adapter.out.persistence.jpa.entity.BudgetAlertRuleJpaEntity
import com.example.billing.domain.budget.BudgetAlertStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface SpringDataBudgetAlertRuleRepository : JpaRepository<BudgetAlertRuleJpaEntity, UUID> {

    fun findByCustomerIdOrderByCreatedAtDesc(customerId: String): List<BudgetAlertRuleJpaEntity>

    /** Evaluate batch — ACTIVE rule 들의 distinct customer 목록. */
    @Query(
        """
        SELECT DISTINCT r.customerId FROM BudgetAlertRuleJpaEntity r
         WHERE r.status = :status
         ORDER BY r.customerId
        """,
    )
    fun findDistinctCustomerIdsByStatus(@Param("status") status: BudgetAlertStatus): List<String>

    fun findByCustomerIdAndStatus(customerId: String, status: BudgetAlertStatus): List<BudgetAlertRuleJpaEntity>
}
