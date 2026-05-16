package com.example.billing.application.port.`in`

import com.example.billing.domain.budget.BudgetAlertRuleId

/**
 * BudgetAlertRule 의 ACTIVE ↔ PAUSED 전이.
 *
 * 일시 정지가 필요한 케이스: 마이그레이션 / 작업 중 알림 끄기 / 운영자 점검.
 * 삭제 (REST DELETE) 는 별도 — 본 use case 는 활성 상태 토글만.
 */
interface BudgetAlertRuleLifecycleUseCase {

    fun pause(ruleId: BudgetAlertRuleId)

    fun resume(ruleId: BudgetAlertRuleId)
}
