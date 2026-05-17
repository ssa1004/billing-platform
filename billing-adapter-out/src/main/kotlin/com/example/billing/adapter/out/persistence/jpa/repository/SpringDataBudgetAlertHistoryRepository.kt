package com.example.billing.adapter.out.persistence.jpa.repository

import com.example.billing.adapter.out.persistence.jpa.entity.BudgetAlertHistoryJpaEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpringDataBudgetAlertHistoryRepository : JpaRepository<BudgetAlertHistoryJpaEntity, UUID> {

    fun findByRuleIdOrderByOccurredAtDesc(ruleId: UUID, pageable: Pageable): List<BudgetAlertHistoryJpaEntity>

    fun findByCustomerIdOrderByOccurredAtDesc(
        customerId: String,
        pageable: Pageable,
    ): List<BudgetAlertHistoryJpaEntity>
}
