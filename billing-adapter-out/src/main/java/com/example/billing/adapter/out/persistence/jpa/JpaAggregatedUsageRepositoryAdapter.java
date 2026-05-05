package com.example.billing.adapter.out.persistence.jpa;

import com.example.billing.adapter.out.persistence.jpa.entity.AggregatedUsageJpaEntity;
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataAggregatedUsageRepository;
import com.example.billing.application.port.out.AggregatedUsageRepository;
import com.example.billing.domain.metering.AggregatedUsage;
import com.example.billing.domain.metering.ResourceType;
import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JpaAggregatedUsageRepositoryAdapter implements AggregatedUsageRepository {

    private final SpringDataAggregatedUsageRepository jpa;

    public JpaAggregatedUsageRepositoryAdapter(SpringDataAggregatedUsageRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(AggregatedUsage aggregate) {
        AggregatedUsageJpaEntity entity = jpa.findByCustomerIdAndResourceTypeAndPeriodYearMonth(
                aggregate.customerId().value(),
                aggregate.resourceType(),
                aggregate.period().toKey())
                .orElseGet(AggregatedUsageJpaEntity::new);
        if (entity.getId() == null) entity.setId(aggregate.id());
        entity.setCustomerId(aggregate.customerId().value());
        entity.setResourceType(aggregate.resourceType());
        entity.setPeriodYearMonth(aggregate.period().toKey());
        entity.setTotalQuantity(aggregate.totalQuantity());
        entity.setEventCount(aggregate.eventCount());
        entity.setAggregatedAt(aggregate.aggregatedAt());
        jpa.save(entity);
    }

    @Override
    public Optional<AggregatedUsage> findBy(CustomerId customerId, ResourceType resourceType,
                                            BillingPeriod period) {
        return jpa.findByCustomerIdAndResourceTypeAndPeriodYearMonth(
                        customerId.value(), resourceType, period.toKey())
                .map(this::toDomain);
    }

    @Override
    public List<AggregatedUsage> findByCustomerAndPeriod(CustomerId customerId, BillingPeriod period) {
        return jpa.findByCustomerIdAndPeriodYearMonth(customerId.value(), period.toKey())
                .stream().map(this::toDomain).toList();
    }

    private AggregatedUsage toDomain(AggregatedUsageJpaEntity e) {
        return AggregatedUsage.restore(
                e.getId(), CustomerId.of(e.getCustomerId()), e.getResourceType(),
                BillingPeriod.of(java.time.YearMonth.parse(e.getPeriodYearMonth())),
                e.getTotalQuantity(), e.getEventCount(), e.getAggregatedAt());
    }
}
