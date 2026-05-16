package com.example.billing.application.port.out

import com.example.billing.domain.budget.BudgetAlertRule
import com.example.billing.domain.budget.BudgetAlertRuleId
import com.example.billing.domain.shared.CustomerId
import java.util.Optional

interface BudgetAlertRuleRepository {

    fun save(rule: BudgetAlertRule)

    fun findById(id: BudgetAlertRuleId): Optional<BudgetAlertRule>

    /** 운영 / 화면 — 한 customer 의 모든 rule (status 무관). */
    fun findByCustomer(customerId: CustomerId): List<BudgetAlertRule>

    /**
     * Evaluate batch — ACTIVE rule 이 1개 이상인 customer id 목록.
     * 페이지마다 호출되도록 limit/offset 추가는 필요해지면.
     */
    fun findCustomersWithActiveRules(): List<CustomerId>

    /** 한 customer 의 ACTIVE rule 들. evaluate batch 가 호출. */
    fun findActiveByCustomer(customerId: CustomerId): List<BudgetAlertRule>
}
