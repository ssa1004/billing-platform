package com.example.billing.application.exception;

import com.example.billing.domain.shared.CustomerId;

import java.time.Instant;

public class PricingPlanNotFoundException extends RuntimeException {
    public PricingPlanNotFoundException(CustomerId customerId, Instant at) {
        super("no effective pricing plan for customer=" + customerId + " at=" + at);
    }
}
