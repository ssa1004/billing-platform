package com.example.billing.application.command;

import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;

import java.util.UUID;

public record SettlementResult(
        CustomerId customerId,
        BillingPeriod period,
        UUID invoiceId,
        boolean alreadyProcessed,
        boolean paymentSucceeded,
        String message
) {
    public static SettlementResult skipped(CustomerId customerId, BillingPeriod period, String reason) {
        return new SettlementResult(customerId, period, null, true, false, reason);
    }
    public static SettlementResult success(CustomerId customerId, BillingPeriod period,
                                           UUID invoiceId, boolean paymentSucceeded) {
        return new SettlementResult(customerId, period, invoiceId, false, paymentSucceeded,
                paymentSucceeded ? "paid" : "invoice issued, payment failed");
    }
}
