package com.example.billing.application.command

import com.example.billing.domain.settlement.BillingPeriod
import com.example.billing.domain.shared.CustomerId

@JvmRecord
data class RunSettlementCommand(val customerId: CustomerId, val period: BillingPeriod)
