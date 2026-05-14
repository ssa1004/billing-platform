package com.example.billing.domain.budget

import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.shared.Money
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Currency
import java.util.Optional

/**
 * 예산 알림 규칙 — "월말 예상 청구액이 X 원 이상이면 알림" 같은 것.
 *
 * 스케줄러가 주기적으로 [evaluate] 를 호출합니다. 임계를 초과하고 cooldown (재트리거 사이의
 * 휴지 간격) 도 지났으면 Triggered 이벤트를 반환합니다. cooldown 으로 같은 사용자에게 동일
 * 알림이 매 분 가는 것을 방지 (기본 24시간).
 *
 * **도메인 invariant**:
 * - `threshold > 0`
 * - 모든 evaluate 호출의 projectedCost 는 `threshold.currency` 와 같아야 함
 *
 * 한 customer 가 여러 rule 을 보유할 수 있습니다 (예: $100 yellow / $500 red 처럼 단계 알림).
 * 도메인은 그 정책을 모릅니다 — application service 가 rule 목록을 돌며 evaluate 를 호출합니다.
 *
 * record-style accessor (`id()` / `status()` / `version()` 등) 는 `@get:JvmName` 으로
 * Java/Kotlin 양쪽 호출자 호환 유지.
 */
class BudgetAlertRule private constructor(
    @get:JvmName("id") val id: BudgetAlertRuleId,
    @get:JvmName("customerId") val customerId: CustomerId,
    @get:JvmName("threshold") val threshold: Money,
    @get:JvmName("cooldown") val cooldown: Duration,
    status: BudgetAlertStatus,
    lastEvaluatedAt: Instant?,
    lastTriggeredAt: Instant?,
    @get:JvmName("createdAt") val createdAt: Instant,
    updatedAt: Instant,
    @get:JvmName("version") val version: Long,
) {

    @get:JvmName("status")
    var status: BudgetAlertStatus = status
        private set

    @get:JvmName("lastEvaluatedAt")
    var lastEvaluatedAt: Instant? = lastEvaluatedAt
        private set

    @get:JvmName("lastTriggeredAt")
    var lastTriggeredAt: Instant? = lastTriggeredAt
        private set

    @get:JvmName("updatedAt")
    var updatedAt: Instant = updatedAt
        private set

    /**
     * 평가. 임계를 초과했고 cooldown 도 지났으면 Triggered 이벤트 반환, 아니면 empty.
     * 트리거되든 안 되든 lastEvaluatedAt 은 항상 갱신합니다.
     *
     * @param projectedCost threshold 와 동일 통화여야 함 (UsageForecast 결과로 들어옴)
     */
    fun evaluate(projectedCost: Money, clock: Clock): Optional<BudgetAlertEvents.Triggered> {
        require(projectedCost.currency == threshold.currency) {
            "currency mismatch: rule=${threshold.currency} projected=${projectedCost.currency}"
        }
        val now = clock.instant()
        this.lastEvaluatedAt = now
        this.updatedAt = now

        if (status != BudgetAlertStatus.ACTIVE) return Optional.empty()
        if (projectedCost.compareTo(threshold) < 0) return Optional.empty()
        val triggeredAt = lastTriggeredAt
        if (triggeredAt != null && Duration.between(triggeredAt, now).compareTo(cooldown) < 0) {
            return Optional.empty() // cooldown 이 아직 안 지남 — 알림 스팸 방지
        }

        this.lastTriggeredAt = now
        val ratio = projectedCost.amount.toDouble() / threshold.amount.toDouble()
        return Optional.of(
            BudgetAlertEvents.Triggered(id, customerId, threshold, projectedCost, ratio, now),
        )
    }

    fun pause(clock: Clock): BudgetAlertEvents.Paused {
        check(status == BudgetAlertStatus.ACTIVE) { "only ACTIVE can be paused: status=$status" }
        val now = clock.instant()
        this.status = BudgetAlertStatus.PAUSED
        this.updatedAt = now
        return BudgetAlertEvents.Paused(id, customerId, now)
    }

    fun resume(clock: Clock): BudgetAlertEvents.Resumed {
        check(status == BudgetAlertStatus.PAUSED) { "only PAUSED can be resumed: status=$status" }
        val now = clock.instant()
        this.status = BudgetAlertStatus.ACTIVE
        this.updatedAt = now
        return BudgetAlertEvents.Resumed(id, customerId, now)
    }

    @JvmName("currency")
    fun currency(): Currency = threshold.currency

    companion object {

        /** 같은 rule 이 다시 트리거되기까지 최소 간격. */
        private val DEFAULT_COOLDOWN: Duration = Duration.ofHours(24)

        @JvmStatic
        fun create(customerId: CustomerId, threshold: Money, clock: Clock): BudgetAlertRule =
            create(customerId, threshold, DEFAULT_COOLDOWN, clock)

        @JvmStatic
        fun create(
            customerId: CustomerId,
            threshold: Money,
            cooldown: Duration,
            clock: Clock,
        ): BudgetAlertRule {
            require(threshold.isPositive) { "threshold must be positive: $threshold" }
            require(!(cooldown.isNegative || cooldown.isZero)) {
                "cooldown must be positive: $cooldown"
            }
            val now = clock.instant()
            return BudgetAlertRule(
                BudgetAlertRuleId.newId(), customerId, threshold, cooldown,
                BudgetAlertStatus.ACTIVE, null, null, now, now, 0L,
            )
        }

        @JvmStatic
        fun restore(
            id: BudgetAlertRuleId,
            customerId: CustomerId,
            threshold: Money,
            cooldown: Duration,
            status: BudgetAlertStatus,
            lastEvaluatedAt: Instant?,
            lastTriggeredAt: Instant?,
            createdAt: Instant,
            updatedAt: Instant,
            version: Long,
        ): BudgetAlertRule = BudgetAlertRule(
            id, customerId, threshold, cooldown, status,
            lastEvaluatedAt, lastTriggeredAt, createdAt, updatedAt, version,
        )
    }
}
