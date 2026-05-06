package com.example.billing.domain.pricing;

import com.example.billing.domain.metering.ResourceType;
import com.example.billing.domain.shared.Money;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 가격 정책 — 한 고객 (또는 한 plan) 의 resourceType 별 tier 묶음.
 *
 * <p>plan 정의가 변경되면 새 PricingPlan 행이 INSERT 되고 effectiveFrom 으로 적용 시점이
 * 결정된다. 과거 정의는 update 가 아니라 별도 행으로 유지 (audit trail).</p>
 *
 * <p>실제 청구서 생성 시에는 {@link PricingSnapshot} 으로 저장되어 요금제 변경에도 과거 청구서는
 * 변하지 않는다.</p>
 */
public final class PricingPlan {

    private final UUID id;
    private final String name;
    private final List<Tier> tiers;
    private final Instant effectiveFrom;

    private PricingPlan(UUID id, String name, List<Tier> tiers, Instant effectiveFrom) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (tiers == null || tiers.isEmpty()) {
            throw new IllegalArgumentException("tiers must not be empty");
        }
        // 같은 resourceType 내에서 upTo 가 오름차순인지 검증
        Map<ResourceType, List<Tier>> byResource = tiers.stream()
                .collect(Collectors.groupingBy(Tier::resourceType));
        for (var entry : byResource.entrySet()) {
            List<Tier> sorted = entry.getValue().stream()
                    .sorted((a, b) -> Long.compare(
                            a.upTo() == null ? Long.MAX_VALUE : a.upTo(),
                            b.upTo() == null ? Long.MAX_VALUE : b.upTo()))
                    .toList();
            if (!sorted.equals(entry.getValue())) {
                // 입력 순서가 정렬 순서와 다르면 안전하게 정렬된 사본으로
                tiers = tiers.stream().sorted((a, b) -> {
                    int c = a.resourceType().compareTo(b.resourceType());
                    if (c != 0) return c;
                    long au = a.upTo() == null ? Long.MAX_VALUE : a.upTo();
                    long bu = b.upTo() == null ? Long.MAX_VALUE : b.upTo();
                    return Long.compare(au, bu);
                }).toList();
                break;
            }
        }
        this.id = Objects.requireNonNull(id);
        this.name = name;
        this.tiers = List.copyOf(tiers);
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom);
    }

    public static PricingPlan create(String name, List<Tier> tiers, Instant effectiveFrom) {
        return new PricingPlan(UUID.randomUUID(), name, tiers, effectiveFrom);
    }

    public static PricingPlan restore(UUID id, String name, List<Tier> tiers, Instant effectiveFrom) {
        return new PricingPlan(id, name, tiers, effectiveFrom);
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public List<Tier> tiers() { return tiers; }
    public Instant effectiveFrom() { return effectiveFrom; }

    /** {@link PricingSnapshot} 으로 저장. 청구서 생성 시 호출. */
    public PricingSnapshot snapshot(Instant capturedAt) {
        return PricingSnapshot.of(id, name, tiers, capturedAt);
    }

    /** 주어진 사용량을 tiered 가격으로 계산. */
    public Money calculate(ResourceType resourceType, long quantity) {
        List<Tier> applicable = tiers.stream()
                .filter(t -> t.resourceType() == resourceType)
                .toList();
        if (applicable.isEmpty()) {
            throw new IllegalStateException("no tier defined for " + resourceType);
        }
        return TieredCalculator.calculate(applicable, quantity);
    }
}
