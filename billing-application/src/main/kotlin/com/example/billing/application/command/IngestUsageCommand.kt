package com.example.billing.application.command

import com.example.billing.domain.metering.ResourceType
import com.example.billing.domain.shared.CustomerId
import java.time.Instant
import java.util.UUID

@JvmRecord
data class IngestUsageCommand(
    val eventId: UUID,
    val customerId: CustomerId,
    val resourceType: ResourceType,
    val quantity: Long,
    val occurredAt: Instant,
)
