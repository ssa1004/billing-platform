package com.example.billing.application.command

import com.example.billing.domain.settlement.BillingPeriod
import com.example.billing.domain.shared.CustomerId
import java.util.UUID

@JvmRecord
data class SettlementResult(
    val customerId: CustomerId,
    val period: BillingPeriod,
    val invoiceId: UUID?,
    val alreadyProcessed: Boolean,
    val paymentSucceeded: Boolean,
    val message: String,
) {
    companion object {
        @JvmStatic
        fun skipped(customerId: CustomerId, period: BillingPeriod, reason: String): SettlementResult =
            SettlementResult(customerId, period, null, true, false, reason)

        @JvmStatic
        fun success(
            customerId: CustomerId,
            period: BillingPeriod,
            invoiceId: UUID,
            paymentSucceeded: Boolean,
        ): SettlementResult = SettlementResult(
            customerId, period, invoiceId, false, paymentSucceeded,
            if (paymentSucceeded) "paid" else "invoice issued, payment failed",
        )
    }
}
