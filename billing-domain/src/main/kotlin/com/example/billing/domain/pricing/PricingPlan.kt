package com.example.billing.domain.pricing

import com.example.billing.domain.metering.ResourceType
import com.example.billing.domain.shared.Money
import java.time.Instant
import java.util.UUID

/**
 * 가격 정책 — 한 고객 (또는 한 plan) 의 resourceType 별 tier 묶음.
 *
 * plan 정의가 변경되면 새 PricingPlan 행이 INSERT 되고 effectiveFrom 으로 적용 시점이
 * 결정된다. 과거 정의는 update 가 아니라 별도 행으로 유지 (audit trail).
 *
 * 실제 청구서 생성 시에는 [PricingSnapshot] 으로 저장되어 요금제 변경에도 과거 청구서는
 * 변하지 않는다.
 *
 * 모든 필드는 final 이라 mutable property 가 없지만 생성자에서 tiers 정렬 / 방어적
 * 복사 / 유효성 검증이 일어나므로 data class 가 아닌 일반 class + private 생성자 +
 * companion factory 패턴 유지. record-style accessor 는 `@get:JvmName` 으로 보존.
 */
class PricingPlan private constructor(
    @get:JvmName("id") val id: UUID,
    @get:JvmName("name") val name: String,
    @get:JvmName("tiers") val tiers: List<Tier>,
    @get:JvmName("effectiveFrom") val effectiveFrom: Instant,
) {

    /** [PricingSnapshot] 으로 저장. 청구서 생성 시 호출. */
    fun snapshot(capturedAt: Instant): PricingSnapshot =
        PricingSnapshot.of(id, name, tiers, capturedAt)

    /** 주어진 사용량을 tiered 가격으로 계산. */
    fun calculate(resourceType: ResourceType, quantity: Long): Money {
        val applicable = tiers.filter { it.resourceType == resourceType }
        check(applicable.isNotEmpty()) { "no tier defined for $resourceType" }
        return TieredCalculator.calculate(applicable, quantity)
    }

    companion object {

        /**
         * 같은 resourceType 안에서 upTo 오름차순 정렬 보장. 입력이 이미 정렬돼 있으면
         * 그대로, 아니면 (resourceType, upTo) 복합 정렬한 사본을 사용.
         */
        private fun normalize(tiers: List<Tier>): List<Tier> {
            require(tiers.isNotEmpty()) { "tiers must not be empty" }
            val byResource = tiers.groupBy { it.resourceType }
            for ((_, group) in byResource) {
                val sorted = group.sortedBy { it.upTo ?: Long.MAX_VALUE }
                if (sorted != group) {
                    // 입력 순서가 정렬 순서와 다르면 안전하게 정렬된 사본으로
                    return tiers.sortedWith(
                        compareBy<Tier> { it.resourceType }
                            .thenBy { it.upTo ?: Long.MAX_VALUE }
                    ).let { java.util.List.copyOf(it) }
                }
            }
            return java.util.List.copyOf(tiers)
        }

        private fun create(id: UUID, name: String, tiers: List<Tier>, effectiveFrom: Instant): PricingPlan {
            require(name.isNotBlank()) { "name must not be blank" }
            return PricingPlan(id, name, normalize(tiers), effectiveFrom)
        }

        @JvmStatic
        fun create(name: String, tiers: List<Tier>, effectiveFrom: Instant): PricingPlan =
            create(UUID.randomUUID(), name, tiers, effectiveFrom)

        @JvmStatic
        fun restore(id: UUID, name: String, tiers: List<Tier>, effectiveFrom: Instant): PricingPlan =
            create(id, name, tiers, effectiveFrom)
    }
}
