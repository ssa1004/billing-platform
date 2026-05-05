package com.example.billing.domain.pricing;

import com.example.billing.domain.shared.Money;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tiered pricing 계산. 누진 (graduated) 방식 — 각 구간에 들어간 수량만큼 해당 tier 가격 적용.
 *
 * <p>예: tiers = [upTo=1000@0원, upTo=10000@1원, upTo=null@0.5원]<br>
 * quantity=15000 → 1000 * 0 + 9000 * 1 + 5000 * 0.5 = 11500원</p>
 */
final class TieredCalculator {

    private TieredCalculator() {}

    static Money calculate(List<Tier> tiers, long quantity) {
        if (quantity <= 0) {
            return Money.zero(tiers.get(0).unitPrice().currency());
        }
        Money total = Money.zero(tiers.get(0).unitPrice().currency());
        long remaining = quantity;
        long covered = 0;
        for (Tier tier : tiers) {
            long tierUpTo = tier.upTo() != null ? tier.upTo() : Long.MAX_VALUE;
            long tierCapacity = tierUpTo - covered;
            if (tierCapacity <= 0) continue;
            long appliedQuantity = Math.min(remaining, tierCapacity);
            Money tierAmount = tier.unitPrice().multiply(BigDecimal.valueOf(appliedQuantity));
            total = total.add(tierAmount);
            remaining -= appliedQuantity;
            covered += appliedQuantity;
            if (remaining <= 0) break;
        }
        return total;
    }
}
