package com.example.billing.domain.pricing

import com.example.billing.domain.shared.Money
import java.math.BigDecimal

/**
 * Tiered pricing 계산. 누진 (graduated) 방식 — 각 구간에 들어간 수량만큼 해당 tier 가격 적용.
 *
 * 예: tiers = `[upTo=1000@0원, upTo=10000@1원, upTo=null@0.5원]`
 * quantity=15000 → 1000 * 0 + 9000 * 1 + 5000 * 0.5 = 11500원
 *
 * stateless 함수 묶음이라 internal object 로 노출. 같은 모듈 내 PricingPlan /
 * PricingSnapshot 만 호출. Java 호출자 / 다른 모듈 노출 없음 (Java 의 package-private
 * 동치).
 */
internal object TieredCalculator {

    fun calculate(tiers: List<Tier>, quantity: Long): Money {
        val currency = tiers[0].unitPrice.currency()
        if (quantity <= 0) {
            return Money.zero(currency)
        }
        var total = Money.zero(currency)
        var remaining = quantity
        var covered = 0L
        for (tier in tiers) {
            val tierUpTo = tier.upTo ?: Long.MAX_VALUE
            val tierCapacity = tierUpTo - covered
            if (tierCapacity <= 0) continue
            val appliedQuantity = minOf(remaining, tierCapacity)
            val tierAmount = tier.unitPrice.multiply(BigDecimal.valueOf(appliedQuantity))
            total = total.add(tierAmount)
            remaining -= appliedQuantity
            covered += appliedQuantity
            if (remaining <= 0) break
        }
        return total
    }
}
