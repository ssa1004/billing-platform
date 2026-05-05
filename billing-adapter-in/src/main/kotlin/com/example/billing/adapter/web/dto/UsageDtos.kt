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
