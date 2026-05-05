package com.example.billing.adapter.web

import com.example.billing.adapter.web.dto.IngestUsageRequest
import com.example.billing.adapter.web.dto.IngestUsageResponse
import com.example.billing.application.command.IngestUsageCommand
import com.example.billing.application.port.`in`.IngestUsageUseCase
import com.example.billing.domain.metering.ResourceType
import com.example.billing.domain.shared.CustomerId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/usage")
@Tag(name = "usage", description = "사용량 이벤트 수신")
class UsageController(
    private val ingestUsage: IngestUsageUseCase,
) {

    @PostMapping
    @Operation(summary = "사용량 이벤트 수신 (eventId 기반 멱등성)")
    fun ingest(@Valid @RequestBody req: IngestUsageRequest): ResponseEntity<IngestUsageResponse> {
        val cmd = IngestUsageCommand(
            UUID.fromString(req.eventId),
            CustomerId.of(req.customerId),
            ResourceType.valueOf(req.resourceType),
            req.quantity,
            Instant.parse(req.occurredAt),
        )
        val accepted = ingestUsage.ingest(cmd)
        return ResponseEntity.accepted().body(IngestUsageResponse(req.eventId, accepted))
    }
}
