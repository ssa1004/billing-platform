package com.example.billing.application.port.out;

import com.example.billing.domain.metering.AggregatedUsage;
import com.example.billing.domain.metering.ResourceType;
import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;

import java.util.List;
import java.util.Optional;

public interface AggregatedUsageRepository {

    void save(AggregatedUsage aggregate);

    Optional<AggregatedUsage> findBy(CustomerId customerId, ResourceType resourceType,
                                     BillingPeriod period);

    List<AggregatedUsage> findByCustomerAndPeriod(CustomerId customerId, BillingPeriod period);
}
