package com.example.billing.application.command

import com.example.billing.domain.shared.Money
import java.time.Duration

/** [cooldown] null 이면 도메인 default (24h) 사용. */
@JvmRecord
data class CreateBudgetAlertRuleCommand(
    val idempotencyKey: String,
    val customerId: String,
    val threshold: Money,
    val cooldown: Duration?,
)
