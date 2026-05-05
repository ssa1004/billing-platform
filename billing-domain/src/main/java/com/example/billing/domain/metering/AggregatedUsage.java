package com.example.billing.domain.metering;

import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 특정 기간(BillingPeriod) 의 특정 customer × resourceType 사용량 집계 결과.
 *
 * <p>일/시간 단위 rollup 도 동일 구조로 표현 (period 만 다름). 월별 집계가 결국 청구서의 한
 * line 이 된다.</p>
 */
public final class AggregatedUsage {

    private final UUID id;
    private final CustomerId customerId;
    private final ResourceType resourceType;
    private final BillingPeriod period;
    private final long totalQuantity;
    private final long eventCount;
    private final Instant aggregatedAt;

    private AggregatedUsage(UUID id, CustomerId customerId, ResourceType resourceType,
                            BillingPeriod period, long totalQuantity, long eventCount,
                            Instant aggregatedAt) {
        if (totalQuantity < 0) {
            throw new IllegalArgumentException("totalQuantity must be non-negative");
        }
        if (eventCount < 0) {
            throw new IllegalArgumentException("eventCount must be non-negative");
        }
        this.id = Objects.requireNonNull(id);
        this.customerId = Objects.requireNonNull(customerId);
        this.resourceType = Objects.requireNonNull(resourceType);
        this.period = Objects.requireNonNull(period);
        this.totalQuantity = totalQuantity;
        this.eventCount = eventCount;
        this.aggregatedAt = Objects.requireNonNull(aggregatedAt);
    }

    public static AggregatedUsage of(CustomerId customerId, ResourceType resourceType,
                                     BillingPeriod period, long totalQuantity, long eventCount,
                                     Instant aggregatedAt) {
        return new AggregatedUsage(UUID.randomUUID(), customerId, resourceType, period,
                totalQuantity, eventCount, aggregatedAt);
    }

    public static AggregatedUsage restore(UUID id, CustomerId customerId, ResourceType resourceType,
                                          BillingPeriod period, long totalQuantity, long eventCount,
                                          Instant aggregatedAt) {
        return new AggregatedUsage(id, customerId, resourceType, period, totalQuantity,
                eventCount, aggregatedAt);
    }

    public UUID id() { return id; }
    public CustomerId customerId() { return customerId; }
    public ResourceType resourceType() { return resourceType; }
    public BillingPeriod period() { return period; }
    public long totalQuantity() { return totalQuantity; }
    public long eventCount() { return eventCount; }
    public Instant aggregatedAt() { return aggregatedAt; }
}
