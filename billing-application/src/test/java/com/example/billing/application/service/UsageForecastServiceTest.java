package com.example.billing.application.service;

import com.example.billing.application.exception.PricingPlanNotFoundException;
import com.example.billing.application.port.out.AggregatedUsageRepository;
import com.example.billing.application.port.out.PricingPlanRepository;
import com.example.billing.domain.metering.AggregatedUsage;
import com.example.billing.domain.metering.ResourceType;
import com.example.billing.domain.metering.UsageForecast;
import com.example.billing.domain.pricing.PricingPlan;
import com.example.billing.domain.pricing.Tier;
import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageForecastServiceTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final CustomerId ALICE = CustomerId.of("alice");
    private static final BillingPeriod MAY_2026 = BillingPeriod.of(YearMonth.of(2026, 5));

    @Mock AggregatedUsageRepository usages;
    @Mock PricingPlanRepository plans;

    private static Money won(long n) {
        return Money.of(BigDecimal.valueOf(n), KRW);
    }

    /** 호출당 1원, 1만건까지 무료, 그 이후 1원. */
    private static PricingPlan flatPlan() {
        Tier free = new Tier(ResourceType.API_CALL, 10_000L, Money.of(BigDecimal.ZERO, KRW));
        Tier paid = new Tier(ResourceType.API_CALL, null, Money.of(BigDecimal.ONE, KRW));
        return PricingPlan.create("test", List.of(free, paid), Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static AggregatedUsage agg(long quantity) {
        return AggregatedUsage.restore(UUID.randomUUID(), ALICE, ResourceType.API_CALL,
                MAY_2026, quantity, 1L, Instant.parse("2026-05-15T00:00:00Z"));
    }

    private UsageForecastService serviceAt(Instant asOf) {
        return new UsageForecastService(usages, plans, Clock.fixed(asOf, ZoneOffset.UTC));
    }

    @Test
    void noPlan_throws() {
        Instant asOf = Instant.parse("2026-05-15T12:00:00Z");
        when(plans.findEffective(ALICE, asOf)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> serviceAt(asOf).forecastCurrentPeriod(ALICE))
                .isInstanceOf(PricingPlanNotFoundException.class);
    }

    @Test
    void midMonth_linearExtrapolation() {
        // 5월 15일 12:00 = 14.5/31 ≈ 0.467 진행률
        Instant asOf = Instant.parse("2026-05-15T12:00:00Z");
        when(plans.findEffective(ALICE, asOf)).thenReturn(Optional.of(flatPlan()));
        when(usages.findByCustomerAndPeriod(ALICE, MAY_2026)).thenReturn(List.of(agg(15_000)));

        UsageForecast f = serviceAt(asOf).forecastCurrentPeriod(ALICE);

        assertThat(f.periodProgressRatio()).isBetween(0.46, 0.48);
        assertThat(f.resources()).hasSize(1);
        var r = f.resources().get(0);
        assertThat(r.mtdQuantity()).isEqualTo(15_000);
        // 15000 / 0.467 ≈ 32_120
        assertThat(r.projectedQuantity()).isBetween(31_500L, 32_500L);
        // 무료 1만 + 유료 (32000 - 10000) = 22000원
        assertThat(r.projectedCost().amount()).isBetween(BigDecimal.valueOf(21_500), BigDecimal.valueOf(22_500));
        assertThat(f.projectedTotalCost()).isEqualTo(r.projectedCost());
    }

    @Test
    void monthEnd_progressOne_projectedEqualsMtd() {
        Instant asOf = Instant.parse("2026-05-31T23:59:59Z");
        when(plans.findEffective(ALICE, asOf)).thenReturn(Optional.of(flatPlan()));
        when(usages.findByCustomerAndPeriod(ALICE, MAY_2026)).thenReturn(List.of(agg(50_000)));

        UsageForecast f = serviceAt(asOf).forecastCurrentPeriod(ALICE);

        var r = f.resources().get(0);
        assertThat(r.projectedQuantity()).isEqualTo(50_000);
        assertThat(r.projectedCost().amount()).isEqualByComparingTo("40000");  // 40k 유료
    }

    @Test
    void monthStart_belowThreshold_noExtrapolation() {
        // 5월 1일 00:00 직후 → progress ≈ 0
        Instant asOf = Instant.parse("2026-05-01T00:00:01Z");
        when(plans.findEffective(ALICE, asOf)).thenReturn(Optional.of(flatPlan()));
        when(usages.findByCustomerAndPeriod(ALICE, MAY_2026)).thenReturn(List.of(agg(100)));

        UsageForecast f = serviceAt(asOf).forecastCurrentPeriod(ALICE);

        // progress < 0.001 → 외삽 안 함
        var r = f.resources().get(0);
        assertThat(r.projectedQuantity()).isEqualTo(r.mtdQuantity());
    }

    @Test
    void emptyUsage_returnsZeroForecast() {
        Instant asOf = Instant.parse("2026-05-15T00:00:00Z");
        when(plans.findEffective(ALICE, asOf)).thenReturn(Optional.of(flatPlan()));
        when(usages.findByCustomerAndPeriod(ALICE, MAY_2026)).thenReturn(List.of());

        UsageForecast f = serviceAt(asOf).forecastCurrentPeriod(ALICE);

        assertThat(f.resources()).isEmpty();
        assertThat(f.projectedTotalCost()).isEqualTo(won(0));
    }

    @Test
    void multipleResources_sumsTotalCost() {
        Instant asOf = Instant.parse("2026-05-16T00:00:00Z");
        // 두 개 ResourceType — API_CALL, DATA_TRANSFER_GB
        Tier apiFree = new Tier(ResourceType.API_CALL, null, Money.of(BigDecimal.ONE, KRW));
        Tier dataAll = new Tier(ResourceType.DATA_TRANSFER_GB, null, Money.of(BigDecimal.TEN, KRW));
        PricingPlan plan = PricingPlan.create("multi", List.of(apiFree, dataAll),
                Instant.parse("2026-01-01T00:00:00Z"));

        AggregatedUsage api = AggregatedUsage.restore(UUID.randomUUID(), ALICE,
                ResourceType.API_CALL, MAY_2026, 1_000, 1L, asOf);
        AggregatedUsage data = AggregatedUsage.restore(UUID.randomUUID(), ALICE,
                ResourceType.DATA_TRANSFER_GB, MAY_2026, 100, 1L, asOf);

        when(plans.findEffective(ALICE, asOf)).thenReturn(Optional.of(plan));
        when(usages.findByCustomerAndPeriod(ALICE, MAY_2026))
                .thenReturn(List.of(api, data));

        UsageForecast f = serviceAt(asOf).forecastCurrentPeriod(ALICE);

        assertThat(f.resources()).hasSize(2);
        // 외삽 quantity * unitPrice 합 — 정확한 값보단 양수 + 두 라인 합 검증
        Money sum = f.resources().stream()
                .map(UsageForecast.ResourceForecast::projectedCost)
                .reduce(Money::add).orElseThrow();
        assertThat(f.projectedTotalCost()).isEqualTo(sum);
        assertThat(f.projectedTotalCost().amount()).isGreaterThan(BigDecimal.ZERO);
    }
}
