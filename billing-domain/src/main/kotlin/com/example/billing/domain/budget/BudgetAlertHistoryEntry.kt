package com.example.billing.domain.budget

import com.example.billing.domain.settlement.BillingPeriod
import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.shared.Money
import java.time.Instant
import java.util.UUID

/**
 * BudgetAlertRule 이 실제로 트리거된 사실의 영속 기록 (append-only).
 *
 * [BudgetAlertRule] 자체는 가장 최근 트리거 시각만 들고 있다 (cooldown 계산 용). 이력 ("최근
 * 30일 내 임계 초과 횟수", "처음 임계 초과 시점") 같은 분석은 별도 테이블이 자연스러움.
 *
 * 아래 필드는 알림 시점의 snapshot — rule 이 나중에 변경돼도 (예: threshold 상향) 과거 트리거의
 * 의미가 변하지 않도록 모두 저장한다.
 *
 * `@JvmRecord data class` — Java 호출자 (`BudgetAlertHistoryJpaMapper` 의 `new
 * BudgetAlertHistoryEntry(...)` 직접 생성자 + `h.id()` / `h.ruleId()` 등 record-style accessor)
 * 무변경. compact constructor 검증은 `init` 블록으로 보존.
 */
@JvmRecord
data class BudgetAlertHistoryEntry(
    val id: UUID,
    val ruleId: BudgetAlertRuleId,
    val customerId: CustomerId,
    val thresholdAtTrigger: Money,
    val projectedCostAtTrigger: Money,
    val overshootRatio: Double,
    val period: BillingPeriod,
    val periodProgressRatioAtTrigger: Double,
    val occurredAt: Instant,
) {

    init {
        require(thresholdAtTrigger.currency == projectedCostAtTrigger.currency) {
            "currency mismatch: threshold=${thresholdAtTrigger.currency}" +
                " projected=${projectedCostAtTrigger.currency}"
        }
        require(overshootRatio >= 1.0) {
            "overshootRatio must be >= 1.0 (triggered means projected >= threshold): $overshootRatio"
        }
        require(periodProgressRatioAtTrigger in 0.0..1.0) {
            "periodProgress out of [0,1]: $periodProgressRatioAtTrigger"
        }
    }

    companion object {
        @JvmStatic
        fun from(
            ev: BudgetAlertEvents.Triggered,
            period: BillingPeriod,
            periodProgressRatio: Double,
        ): BudgetAlertHistoryEntry = BudgetAlertHistoryEntry(
            UUID.randomUUID(),
            ev.ruleId,
            ev.customerId,
            ev.threshold,
            ev.projectedCost,
            ev.overshootRatio,
            period,
            periodProgressRatio,
            ev.occurredAt(),
        )
    }
}
