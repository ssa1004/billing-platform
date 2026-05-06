package com.example.billing.adapter.web.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero

data class IngestUsageRequest(
    @field:NotBlank
    @field:Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    val eventId: String,

    @field:NotBlank val customerId: String,

    @field:NotBlank val resourceType: String,

    @field:PositiveOrZero val quantity: Long,

    @field:NotBlank val occurredAt: String,  // ISO-8601 UTC
)

data class IngestUsageResponse(val eventId: String, val accepted: Boolean)

/** 월말 예상 응답. */
data class UsageForecastResponse(
    val customerId: String,
    val period: String,                  // "2026-05"
    val asOf: String,                    // ISO-8601
    val periodProgressRatio: Double,     // 0.0 ~ 1.0
    val resources: List<ForecastResourceView>,
    val projectedTotalCost: java.math.BigDecimal,
    val currency: String,
)

data class ForecastResourceView(
    val resourceType: String,
    val mtdQuantity: Long,
    val projectedQuantity: Long,
    val mtdCost: java.math.BigDecimal,
    val projectedCost: java.math.BigDecimal,
)
