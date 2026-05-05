package com.example.billing.application.command;

import com.example.billing.domain.metering.ResourceType;
import com.example.billing.domain.shared.CustomerId;

import java.time.Instant;
import java.util.UUID;

public record IngestUsageCommand(
        UUID eventId,
        CustomerId customerId,
        ResourceType resourceType,
        long quantity,
        Instant occurredAt
) {}
