package com.example.billing.application.port.`in`

interface EvaluateBudgetAlertsUseCase {

    /**
     * ACTIVE rule 이 있는 모든 customer 에 대해 forecast 계산 + 각 rule 평가.
     * 임계 초과 (cooldown 지난) rule 은 Triggered 이벤트 발행 + customer 알림.
     *
     * @return 평가된 customer 수
     */
    fun evaluateAll(): Int
}
