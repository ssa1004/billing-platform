package com.example.billing.application.service;

import com.example.billing.application.exception.PricingPlanNotFoundException;
import com.example.billing.application.port.in.UsageForecastUseCase;
import com.example.billing.application.port.out.AggregatedUsageRepository;
import com.example.billing.application.port.out.PricingPlanRepository;
import com.example.billing.domain.metering.AggregatedUsage;
import com.example.billing.domain.metering.UsageForecast;
import com.example.billing.domain.metering.UsageForecast.ResourceForecast;
import com.example.billing.domain.pricing.PricingPlan;
import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 월말 사용량 / 비용 예상 (read model).
 *
 * <p>알고리즘: 월 진행률 기반 선형 외삽.
 * <pre>
 *   progress = (asOf - periodStart) / (periodEnd - periodStart)   ∈ (0, 1]
 *   projectedQuantity = mtdQuantity / progress
 *   projectedCost     = pricingPlan.calculate(projectedQuantity)
 * </pre>
 *
 * <p>한계:
 * <ul>
 *   <li>주기성 (예: 평일/주말 패턴) 무시 — 7일 평균 / time-series 모델로 개선 가능</li>
 *   <li>plan 변경이 월 중간에 발생하면 부정확 — 현재 시점 plan 으로만 계산</li>
 *   <li>월 초 (progress &lt; 5%) 에는 외삽 신뢰도 낮음 — 화면 측에서 progress 노출하여 처리</li>
 * </ul>
 *
 * <p>현재 plan 이 없는 customer 는 {@link PricingPlanNotFoundException}. 무료 tier 만
 * 쓰는 고객도 plan 정의는 필요 (전 tier 가격 0원이라도).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UsageForecastService implements UsageForecastUseCase {

    /** 외삽 분모가 0 에 너무 가까우면 폭발. 이 미만이면 외삽 안 함 (mtd == projected). */
    private static final double MIN_PROGRESS_FOR_EXTRAPOLATION = 0.001;

    private final AggregatedUsageRepository aggregatedUsages;
    private final PricingPlanRepository pricingPlans;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public UsageForecast forecastCurrentPeriod(CustomerId customerId) {
        Instant asOf = clock.instant();
        BillingPeriod period = BillingPeriod.containing(asOf);

        PricingPlan plan = pricingPlans.findEffective(customerId, asOf)
                .orElseThrow(() -> new PricingPlanNotFoundException(customerId, asOf));

        double progress = computeProgress(period, asOf);
        List<AggregatedUsage> mtd = aggregatedUsages.findByCustomerAndPeriod(customerId, period);

        List<ResourceForecast> resources = new ArrayList<>(mtd.size());
        Money totalProjected = Money.zero(currencyOf(plan));

        for (AggregatedUsage usage : mtd) {
            long mtdQty = usage.totalQuantity();
            long projectedQty = projectQuantity(mtdQty, progress);
            Money mtdCost = plan.calculate(usage.resourceType(), mtdQty);
            Money projectedCost = plan.calculate(usage.resourceType(), projectedQty);
            resources.add(new ResourceForecast(
                    usage.resourceType(), mtdQty, projectedQty, mtdCost, projectedCost));
            totalProjected = totalProjected.add(projectedCost);
        }

        log.debug("forecast customer={} period={} progress={} resources={} total={}",
                customerId, period, progress, resources.size(), totalProjected);
        return new UsageForecast(customerId, period, asOf, progress, resources, totalProjected);
    }

    private double computeProgress(BillingPeriod period, Instant asOf) {
        Instant start = period.fromInclusive();
        Instant end = period.toExclusive();
        if (!asOf.isAfter(start)) return 0.0;
        if (!asOf.isBefore(end)) return 1.0;
        long elapsed = Duration.between(start, asOf).toMillis();
        long total = Duration.between(start, end).toMillis();
        return (double) elapsed / total;
    }

    private long projectQuantity(long mtdQuantity, double progress) {
        if (progress <= MIN_PROGRESS_FOR_EXTRAPOLATION) return mtdQuantity;   // 신뢰도 너무 낮음
        if (progress >= 1.0) return mtdQuantity;                              // 기간 종료
        return Math.round(mtdQuantity / progress);
    }

    private static java.util.Currency currencyOf(PricingPlan plan) {
        // PricingPlan 의 어떤 tier 든 같은 통화 — 첫 tier 통화 사용
        return plan.tiers().get(0).unitPrice().currency();
    }
}
