package com.example.billing.application.command

import com.example.billing.domain.credit.CreditType
import com.example.billing.domain.shared.Money
import java.time.Instant

@JvmRecord
data class GrantCreditCommand(
    val idempotencyKey: String,
    val customerId: String,
    val type: CreditType,
    val amount: Money,
    val validFrom: Instant,
    /** nullable */
    val validUntil: Instant?,
    val reason: String?,
)
