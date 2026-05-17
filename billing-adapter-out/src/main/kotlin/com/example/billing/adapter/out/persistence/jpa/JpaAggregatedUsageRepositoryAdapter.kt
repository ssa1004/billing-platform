package com.example.billing.adapter.out.persistence.jpa

import com.example.billing.adapter.out.persistence.jpa.entity.AggregatedUsageJpaEntity
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataAggregatedUsageRepository
import com.example.billing.application.port.out.AggregatedUsageRepository
import com.example.billing.domain.metering.AggregatedUsage
import com.example.billing.domain.metering.ResourceType
import com.example.billing.domain.settlement.BillingPeriod
import com.example.billing.domain.shared.CustomerId
import org.springframework.stereotype.Repository
import java.time.YearMonth
import java.util.Optional

@Repository
class JpaAggregatedUsageRepositoryAdapter(
    private val jpa: SpringDataAggregatedUsageRepository,
) : AggregatedUsageRepository {

    override fun save(aggregate: AggregatedUsage) {
        val entity = jpa.findByCustomerIdAndResourceTypeAndPeriodYearMonth(
            aggregate.customerId.value,
            aggregate.resourceType,
            aggregate.period.toKey(),
        ).orElseGet { AggregatedUsageJpaEntity() }
        if (entity.id == null) entity.id = aggregate.id
        entity.customerId = aggregate.customerId.value
        entity.resourceType = aggregate.resourceType
        entity.periodYearMonth = aggregate.period.toKey()
        entity.totalQuantity = aggregate.totalQuantity
        entity.eventCount = aggregate.eventCount
        entity.aggregatedAt = aggregate.aggregatedAt
        jpa.save(entity)
    }

    override fun findBy(
        customerId: CustomerId,
        resourceType: ResourceType,
        period: BillingPeriod,
    ): Optional<AggregatedUsage> =
        jpa.findByCustomerIdAndResourceTypeAndPeriodYearMonth(customerId.value, resourceType, period.toKey())
            .map(::toDomain)

    override fun findByCustomerAndPeriod(customerId: CustomerId, period: BillingPeriod): List<AggregatedUsage> =
        jpa.findByCustomerIdAndPeriodYearMonth(customerId.value, period.toKey())
            .map(::toDomain)

    private fun toDomain(e: AggregatedUsageJpaEntity): AggregatedUsage = AggregatedUsage.restore(
        e.id!!,
        CustomerId.of(e.customerId),
        e.resourceType,
        BillingPeriod.of(YearMonth.parse(e.periodYearMonth)),
        e.totalQuantity,
        e.eventCount,
        e.aggregatedAt,
    )
}
