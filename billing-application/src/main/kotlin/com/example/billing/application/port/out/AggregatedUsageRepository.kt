package com.example.billing.application.port.out

import com.example.billing.domain.metering.AggregatedUsage
import com.example.billing.domain.metering.ResourceType
import com.example.billing.domain.settlement.BillingPeriod
import com.example.billing.domain.shared.CustomerId
import java.util.Optional

interface AggregatedUsageRepository {

    fun save(aggregate: AggregatedUsage)

    fun findBy(customerId: CustomerId, resourceType: ResourceType, period: BillingPeriod): Optional<AggregatedUsage>

    fun findByCustomerAndPeriod(customerId: CustomerId, period: BillingPeriod): List<AggregatedUsage>
}
