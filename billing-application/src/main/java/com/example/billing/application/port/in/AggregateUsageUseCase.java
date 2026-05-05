package com.example.billing.application.port.in;

import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;

public interface AggregateUsageUseCase {

    /**
     * 한 customer × 한 BillingPeriod 의 raw UsageEvent 들을 합쳐 AggregatedUsage 로 저장.
     * 이미 집계 결과가 있으면 덮어쓴다 (재실행 안전).
     */
    int aggregate(CustomerId customerId, BillingPeriod period);
}
