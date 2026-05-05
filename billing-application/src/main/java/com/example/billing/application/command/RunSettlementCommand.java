package com.example.billing.application.command;

import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;

public record RunSettlementCommand(CustomerId customerId, BillingPeriod period) {}
