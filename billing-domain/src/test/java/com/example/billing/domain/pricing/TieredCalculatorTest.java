package com.example.billing.domain.pricing;

import com.example.billing.domain.metering.ResourceType;
import com.example.billing.domain.shared.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TieredCalculatorTest {

    private static final Currency KRW = Currency.getInstance("KRW");

    @Test
    void 단일_tier_단순_곱셈() {
        PricingPlan plan = PricingPlan.create("Flat", List.of(
                new Tier(ResourceType.API_CALL, null, Money.of(BigDecimal.valueOf(2L), KRW))
        ), Instant.now());
        Money result = plan.calculate(ResourceType.API_CALL, 1000L);
        assertThat(result.amount()).isEqualByComparingTo("2000");
    }

    @Test
    void 첫_tier_무료_초과분만_과금() {
        PricingPlan plan = PricingPlan.create("Freemium", List.of(
                new Tier(ResourceType.API_CALL, 10000L, Money.of(BigDecimal.ZERO, KRW)),
                new Tier(ResourceType.API_CALL, null, Money.of(BigDecimal.valueOf(1L), KRW))
        ), Instant.now());

        // 1만 이하: 0원
        assertThat(plan.calculate(ResourceType.API_CALL, 5000L).amount())
                .isEqualByComparingTo("0");
        // 1만 정확히: 0원
        assertThat(plan.calculate(ResourceType.API_CALL, 10000L).amount())
                .isEqualByComparingTo("0");
        // 1만 + 5000: 5000원 (초과분만)
        assertThat(plan.calculate(ResourceType.API_CALL, 15000L).amount())
                .isEqualByComparingTo("5000");
    }

    @Test
    void 누진_3_tier() {
        PricingPlan plan = PricingPlan.create("Pro", List.of(
                new Tier(ResourceType.API_CALL, 1000L, Money.of(BigDecimal.ZERO, KRW)),
                new Tier(ResourceType.API_CALL, 10000L, Money.of(BigDecimal.valueOf(1L), KRW)),
                new Tier(ResourceType.API_CALL, null, Money.of(BigDecimal.valueOf(2L), KRW))
        ), Instant.now());

        // 15000 = 1000 * 0 + 9000 * 1 + 5000 * 2 = 19000
        assertThat(plan.calculate(ResourceType.API_CALL, 15000L).amount())
                .isEqualByComparingTo("19000");
    }

    @Test
    void 사용량_0_이면_0원() {
        PricingPlan plan = PricingPlan.create("Any", List.of(
                new Tier(ResourceType.STORAGE_GB_HOUR, null, Money.of(BigDecimal.ONE, KRW))
        ), Instant.now());
        assertThat(plan.calculate(ResourceType.STORAGE_GB_HOUR, 0L).amount())
                .isEqualByComparingTo("0");
    }

    @Test
    void snapshot_으로_저장된_가격은_그대로() {
        PricingPlan plan = PricingPlan.create("Original", List.of(
                new Tier(ResourceType.API_CALL, null, Money.of(BigDecimal.valueOf(1L), KRW))
        ), Instant.parse("2026-01-01T00:00:00Z"));
        PricingSnapshot snapshot = plan.snapshot(Instant.parse("2026-05-31T00:00:00Z"));

        // 같은 quantity 에 대해 plan 과 snapshot 결과가 동일
        assertThat(snapshot.calculate(ResourceType.API_CALL, 1000L).amount())
                .isEqualByComparingTo(plan.calculate(ResourceType.API_CALL, 1000L).amount());
    }
}
