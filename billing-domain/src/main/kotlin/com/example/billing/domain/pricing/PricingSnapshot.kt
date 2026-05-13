package com.example.billing.domain.pricing

import com.example.billing.domain.metering.ResourceType
import com.example.billing.domain.shared.Money
import java.time.Instant
import java.util.UUID

/**
 * 청구서 생성 시점의 가격 정책 스냅샷. Invoice 가 직접 보유한다.
 *
 * 왜 snapshot 인가 — plan 정의가 나중에 바뀌어도 과거 청구서의 정산 금액이 변하지 않아야
 * 한다. 회계/감사 요구사항. (FeeSnapshot 패턴과 동일)
 *
 * `@JvmRecord data class` — Java 호출자 (`s.planId()` / `s.planName()` / `s.tiers()`
 * / `s.capturedAt()` record-style accessor) 그대로 동작.
 */
@JvmRecord
data class PricingSnapshot(
    val planId: UUID,
    val planName: String,
    val tiers: List<Tier>,
    val capturedAt: Instant,
) {

    /** 스냅샷 시점의 가격으로 계산. */
    fun calculate(resourceType: ResourceType, quantity: Long): Money {
        val applicable = tiers.filter { it.resourceType == resourceType }
        if (applicable.isEmpty()) {
            return Money.zero(tiers[0].unitPrice.currency())
        }
        return TieredCalculator.calculate(applicable, quantity)
    }

    companion object {
        /**
         * factory. Java record 의 compact constructor 에서 List.copyOf(tiers) 로 했던
         * 방어적 복사를 여기서 수행. 외부 의 `new PricingSnapshot(...)` 직접 호출자가 없어
         * 모든 입구를 of() 로 통일.
         */
        @JvmStatic
        fun of(
            planId: UUID,
            planName: String,
            tiers: List<Tier>,
            capturedAt: Instant,
        ): PricingSnapshot = PricingSnapshot(planId, planName, java.util.List.copyOf(tiers), capturedAt)
    }
}
