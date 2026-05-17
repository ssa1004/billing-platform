package com.example.billing.adapter.out.persistence.jpa.repository

import com.example.billing.adapter.out.persistence.jpa.entity.AggregatedUsageJpaEntity
import com.example.billing.domain.metering.ResourceType
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface SpringDataAggregatedUsageRepository : JpaRepository<AggregatedUsageJpaEntity, UUID> {

    fun findByCustomerIdAndResourceTypeAndPeriodYearMonth(
        customerId: String,
        resourceType: ResourceType,
        periodYearMonth: String,
    ): Optional<AggregatedUsageJpaEntity>

    fun findByCustomerIdAndPeriodYearMonth(
        customerId: String,
        periodYearMonth: String,
    ): List<AggregatedUsageJpaEntity>
}
