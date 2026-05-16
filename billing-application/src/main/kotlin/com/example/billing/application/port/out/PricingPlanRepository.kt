package com.example.billing.application.port.out

import com.example.billing.domain.pricing.PricingPlan
import com.example.billing.domain.shared.CustomerId
import java.time.Instant
import java.util.Optional

interface PricingPlanRepository {

    fun save(plan: PricingPlan)

    /** 주어진 시점에 customer 에게 적용되는 플랜. */
    fun findEffective(customerId: CustomerId, at: Instant): Optional<PricingPlan>
}
