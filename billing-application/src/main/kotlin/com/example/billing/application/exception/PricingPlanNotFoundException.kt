package com.example.billing.application.exception

import com.example.billing.domain.shared.CustomerId
import java.time.Instant

class PricingPlanNotFoundException(customerId: CustomerId, at: Instant) :
    RuntimeException("no effective pricing plan for customer=$customerId at=$at")
