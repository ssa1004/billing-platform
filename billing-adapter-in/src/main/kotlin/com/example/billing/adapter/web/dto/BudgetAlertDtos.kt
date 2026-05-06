package com.example.billing.adapter.web.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.math.BigDecimal

data class CreateBudgetAlertRuleRequest(
    @field:NotBlank val customerId: String,
    @field:Positive val threshold: BigDecimal,
    @field:NotBlank val currency: String,
    /** ISO-8601 duration ("PT24H", "P7D"). null 이면 도메인 기본값 (24h). */
    val cooldown: String? = null,
)

data class BudgetAlertRuleView(
    val id: String,
    val customerId: String,
    val threshold: BigDecimal,
    val currency: String,
    val cooldownSeconds: Long,
    val status: String,
    val lastEvaluatedAt: String?,
    val lastTriggeredAt: String?,
    val createdAt: String,
)

data class BudgetAlertRuleListResponse(val items: List<BudgetAlertRuleView>)

data class BudgetAlertHistoryView(
    val id: String,
    val ruleId: String,
    val customerId: String,
    val thresholdAtTrigger: BigDecimal,
    val projectedCostAtTrigger: BigDecimal,
    val currency: String,
    val overshootRatio: Double,
    val period: String,
    val periodProgressRatioAtTrigger: Double,
    val occurredAt: String,
)

data class BudgetAlertHistoryResponse(val items: List<BudgetAlertHistoryView>)
