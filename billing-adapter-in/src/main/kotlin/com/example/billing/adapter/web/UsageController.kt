package com.example.billing.adapter.web

import com.example.billing.adapter.web.auth.Caller
import com.example.billing.adapter.web.dto.ForecastResourceView
import com.example.billing.adapter.web.dto.IngestUsageRequest
import com.example.billing.adapter.web.dto.IngestUsageResponse
import com.example.billing.adapter.web.dto.UsageForecastResponse
import com.example.billing.application.command.IngestUsageCommand
import com.example.billing.application.port.`in`.IngestUsageUseCase
import com.example.billing.application.port.`in`.UsageForecastUseCase
import com.example.billing.domain.metering.ResourceType
import com.example.billing.domain.shared.CustomerId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * 사용량 이벤트 수신 / 예측 API.
 *
 * <p><b>OWASP API1 BOLA + API6</b>: ingestion / forecast 모두 caller 의 customer 자원에만
 * 접근 허용. customer A 가 customer B 명의로 usage event 를 박아 청구를 조작하는 사고 방지.</p>
 */
@RestController
@RequestMapping("/api/v1/usage")
@Tag(name = "usage", description = "사용량 이벤트 수신 / 예측")
class UsageController(
    private val ingestUsage: IngestUsageUseCase,
    private val forecast: UsageForecastUseCase,
) {

    @PostMapping
    @Operation(summary = "사용량 이벤트 수신 (eventId 기반 멱등성)")
    fun ingest(
        @Valid @RequestBody req: IngestUsageRequest,
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<IngestUsageResponse> {
        Caller.from(jwt).requireOwnerOrAdmin(req.customerId)
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

    @GetMapping("/forecast")
    @Operation(summary = "현재 BillingPeriod 의 월말 사용량 + 예상 청구 금액")
    fun forecastCurrentPeriod(
        @RequestParam customerId: String,
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<UsageForecastResponse> {
        Caller.from(jwt).requireOwnerOrAdmin(customerId)
        val f = forecast.forecastCurrentPeriod(CustomerId.of(customerId))
        return ResponseEntity.ok(
            UsageForecastResponse(
                customerId = f.customerId().value(),
                period = f.period().toKey(),
                asOf = f.asOf().toString(),
                periodProgressRatio = f.periodProgressRatio(),
                resources = f.resources().map { r ->
                    ForecastResourceView(
                        resourceType = r.resourceType().name,
                        mtdQuantity = r.mtdQuantity(),
                        projectedQuantity = r.projectedQuantity(),
                        mtdCost = r.mtdCost().amount(),
                        projectedCost = r.projectedCost().amount(),
                    )
                },
                projectedTotalCost = f.projectedTotalCost().amount(),
                currency = f.projectedTotalCost().currency().currencyCode,
            )
        )
    }
}
