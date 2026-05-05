package com.example.billing.application.service;

import com.example.billing.application.port.in.AggregateUsageUseCase;
import com.example.billing.application.port.out.AggregatedUsageRepository;
import com.example.billing.application.port.out.UsageEventRepository;
import com.example.billing.domain.metering.AggregatedUsage;
import com.example.billing.domain.metering.ResourceType;
import com.example.billing.domain.metering.UsageEvent;
import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UsageEvent → AggregatedUsage rollup. 월말 정산 직전에 실행되는 batch step.
 *
 * <p>대용량 customer 의 경우 streaming aggregation 으로 확장 가능 (현재는 in-memory grouping).
 * 1개월 × 1 customer 의 이벤트가 100만 건을 넘기 시작하면 chunk-based 처리로 전환 필요.</p>
 */
@Service
public class AggregateUsageService implements AggregateUsageUseCase {

    private static final int FETCH_PAGE_SIZE = 5000;

    private final UsageEventRepository usageEventRepository;
    private final AggregatedUsageRepository aggregatedRepository;
    private final Clock clock;

    public AggregateUsageService(UsageEventRepository usageEventRepository,
                                 AggregatedUsageRepository aggregatedRepository,
                                 Clock clock) {
        this.usageEventRepository = usageEventRepository;
        this.aggregatedRepository = aggregatedRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int aggregate(CustomerId customerId, BillingPeriod period) {
        // 단순화: 전체 fetch 후 in-memory grouping. 대규모는 cursor + chunk 로 전환.
        List<UsageEvent> events = usageEventRepository.findInRange(
                period.fromInclusive(), period.toExclusive(), FETCH_PAGE_SIZE);

        Map<ResourceType, long[]> rollup = new HashMap<>();  // [totalQuantity, eventCount]
        for (UsageEvent event : events) {
            if (!event.customerId().equals(customerId)) continue;
            long[] acc = rollup.computeIfAbsent(event.resourceType(), k -> new long[2]);
            acc[0] += event.quantity();
            acc[1] += 1;
        }

        for (var entry : rollup.entrySet()) {
            AggregatedUsage agg = AggregatedUsage.of(customerId, entry.getKey(), period,
                    entry.getValue()[0], entry.getValue()[1], clock.instant());
            aggregatedRepository.save(agg);
        }
        return rollup.size();
    }
}
