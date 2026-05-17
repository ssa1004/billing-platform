package com.example.billing.adapter.out.persistence.jpa

import com.example.billing.adapter.out.persistence.jpa.mapper.BudgetAlertHistoryJpaMapper
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataBudgetAlertHistoryRepository
import com.example.billing.application.port.out.BudgetAlertHistoryRepository
import com.example.billing.domain.budget.BudgetAlertHistoryEntry
import com.example.billing.domain.budget.BudgetAlertRuleId
import com.example.billing.domain.shared.CustomerId
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository

@Repository
class JpaBudgetAlertHistoryRepositoryAdapter(
    private val jpa: SpringDataBudgetAlertHistoryRepository,
) : BudgetAlertHistoryRepository {

    override fun save(entry: BudgetAlertHistoryEntry) {
        jpa.save(BudgetAlertHistoryJpaMapper.toEntity(entry))
    }

    override fun findByRule(ruleId: BudgetAlertRuleId, limit: Int): List<BudgetAlertHistoryEntry> =
        jpa.findByRuleIdOrderByOccurredAtDesc(ruleId.value, PageRequest.of(0, limit))
            .map(BudgetAlertHistoryJpaMapper::toDomain)

    override fun findByCustomer(customerId: CustomerId, limit: Int): List<BudgetAlertHistoryEntry> =
        jpa.findByCustomerIdOrderByOccurredAtDesc(customerId.value, PageRequest.of(0, limit))
            .map(BudgetAlertHistoryJpaMapper::toDomain)
}
