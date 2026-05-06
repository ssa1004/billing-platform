package com.example.billing.application.port.out;

import com.example.billing.domain.budget.BudgetAlertRule;
import com.example.billing.domain.budget.BudgetAlertRuleId;
import com.example.billing.domain.shared.CustomerId;

import java.util.List;
import java.util.Optional;

public interface BudgetAlertRuleRepository {

    void save(BudgetAlertRule rule);

    Optional<BudgetAlertRule> findById(BudgetAlertRuleId id);

    /** 운영 / 화면 — 한 customer 의 모든 rule (status 무관). */
    List<BudgetAlertRule> findByCustomer(CustomerId customerId);

    /**
     * Evaluate batch — ACTIVE rule 이 1개 이상인 customer id 목록.
     * 페이지마다 호출되도록 limit/offset 추가는 필요해지면.
     */
    List<CustomerId> findCustomersWithActiveRules();

    /** 한 customer 의 ACTIVE rule 들. evaluate batch 가 호출. */
    List<BudgetAlertRule> findActiveByCustomer(CustomerId customerId);
}
