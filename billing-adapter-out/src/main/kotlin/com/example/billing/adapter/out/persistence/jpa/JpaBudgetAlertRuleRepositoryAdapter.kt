package com.example.billing.adapter.out.persistence.jpa

import com.example.billing.adapter.out.persistence.jpa.mapper.BudgetAlertRuleJpaMapper
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataBudgetAlertRuleRepository
import com.example.billing.application.port.out.BudgetAlertRuleRepository
import com.example.billing.domain.budget.BudgetAlertRule
import com.example.billing.domain.budget.BudgetAlertRuleId
import com.example.billing.domain.budget.BudgetAlertStatus
import com.example.billing.domain.shared.CustomerId
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
class JpaBudgetAlertRuleRepositoryAdapter(
    private val jpa: SpringDataBudgetAlertRuleRepository,
) : BudgetAlertRuleRepository {

    override fun save(rule: BudgetAlertRule) {
        jpa.save(BudgetAlertRuleJpaMapper.toEntity(rule))
    }

    override fun findById(id: BudgetAlertRuleId): Optional<BudgetAlertRule> =
        jpa.findById(id.value).map(BudgetAlertRuleJpaMapper::toDomain)

    override fun findByCustomer(customerId: CustomerId): List<BudgetAlertRule> =
        jpa.findByCustomerIdOrderByCreatedAtDesc(customerId.value)
            .map(BudgetAlertRuleJpaMapper::toDomain)

    override fun findCustomersWithActiveRules(): List<CustomerId> =
        jpa.findDistinctCustomerIdsByStatus(BudgetAlertStatus.ACTIVE)
            .map(CustomerId::of)

    override fun findActiveByCustomer(customerId: CustomerId): List<BudgetAlertRule> =
        jpa.findByCustomerIdAndStatus(customerId.value, BudgetAlertStatus.ACTIVE)
            .map(BudgetAlertRuleJpaMapper::toDomain)
}
