package com.example.billing.domain.pricing;

import com.example.billing.domain.metering.ResourceType;
import com.example.billing.domain.shared.Money;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 청구서 생성 시점의 가격 정책 스냅샷. Invoice 가 직접 보유한다.
 *
 * <p>왜 snapshot 인가 — plan 정의가 나중에 바뀌어도 과거 청구서의 정산 금액이 변하지 않아야
 * 한다. 회계/감사 요구사항. (FeeSnapshot 패턴과 동일)</p>
 */
public record PricingSnapshot(UUID planId, String planName, List<Tier> tiers, Instant capturedAt) {

    public PricingSnapshot {
        Objects.requireNonNull(planId);
        Objects.requireNonNull(planName);
        Objects.requireNonNull(tiers);
        Objects.requireNonNull(capturedAt);
        tiers = List.copyOf(tiers);
    }

    public static PricingSnapshot of(UUID planId, String planName, List<Tier> tiers, Instant capturedAt) {
        return new PricingSnapshot(planId, planName, tiers, capturedAt);
    }

    /** 스냅샷 시점의 가격으로 계산. */
    public Money calculate(ResourceType resourceType, long quantity) {
        List<Tier> applicable = tiers.stream()
                .filter(t -> t.resourceType() == resourceType)
                .toList();
        if (applicable.isEmpty()) {
            return Money.zero(tiers.get(0).unitPrice().currency());
        }
        return TieredCalculator.calculate(applicable, quantity);
    }
}
