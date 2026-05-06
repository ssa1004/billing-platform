package com.example.billing.adapter.web

import com.example.billing.adapter.web.dto.RegisterWebhookEndpointRequest
import com.example.billing.adapter.web.dto.RegisterWebhookEndpointResponse
import com.example.billing.adapter.web.dto.RotateSecretResponse
import com.example.billing.adapter.web.dto.WebhookDeliveryListResponse
import com.example.billing.adapter.web.dto.WebhookDeliveryView
import com.example.billing.adapter.web.dto.WebhookEndpointListResponse
import com.example.billing.adapter.web.dto.WebhookEndpointView
import com.example.billing.application.command.RegisterWebhookEndpointCommand
import com.example.billing.application.port.`in`.RegisterWebhookEndpointUseCase
import com.example.billing.application.port.`in`.ReplayWebhookDeliveryUseCase
import com.example.billing.application.port.`in`.WebhookEndpointLifecycleUseCase
import com.example.billing.application.port.out.WebhookDeliveryRepository
import com.example.billing.application.port.out.WebhookEndpointRepository
import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.webhook.WebhookDelivery
import com.example.billing.domain.webhook.WebhookDeliveryId
import com.example.billing.domain.webhook.WebhookDeliveryStatus
import com.example.billing.domain.webhook.WebhookEndpoint
import com.example.billing.domain.webhook.WebhookEndpointId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Webhook 운영 API.
 *
 * <p>두 갈래:
 * <ul>
 *   <li>Endpoint CRUD — customer 가 자기 수신 URL 등록/관리</li>
 *   <li>Delivery 조회/replay — 발송 이력 + dead letter 수동 재시도</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@Tag(name = "webhook", description = "Webhook endpoint 등록 / delivery 조회·재시도")
class WebhookController(
    private val registerEndpoint: RegisterWebhookEndpointUseCase,
    private val lifecycle: WebhookEndpointLifecycleUseCase,
    private val replayDelivery: ReplayWebhookDeliveryUseCase,
    private val endpoints: WebhookEndpointRepository,
    private val deliveries: WebhookDeliveryRepository,
) {

    // ─── Endpoints ───────────────────────────────────────────

    @PostMapping("/endpoints")
    @Operation(summary = "Endpoint 등록 — secret 은 응답에 한 번만 평문 노출")
    fun register(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody req: RegisterWebhookEndpointRequest,
    ): ResponseEntity<RegisterWebhookEndpointResponse> {
        val cmd = RegisterWebhookEndpointCommand(
            idempotencyKey,
            req.customerId,
            req.url,
            req.subscribedEventTypes,
        )
        val endpoint = registerEndpoint.register(cmd)
        return ResponseEntity.ok(
            RegisterWebhookEndpointResponse(
                id = endpoint.id().toString(),
                customerId = endpoint.customerId().value(),
                url = endpoint.url(),
                secret = endpoint.secret(),
                subscribedEventTypes = endpoint.subscribedEventTypes(),
                status = endpoint.status().name,
            )
        )
    }

    @GetMapping("/endpoints")
    @Operation(summary = "Customer 의 endpoint 목록 (secret 미포함)")
    fun listEndpoints(@RequestParam customerId: String): ResponseEntity<WebhookEndpointListResponse> {
        val items = endpoints.findByCustomer(CustomerId.of(customerId)).map(::toEndpointView)
        return ResponseEntity.ok(WebhookEndpointListResponse(items = items))
    }

    @PostMapping("/endpoints/{id}/pause")
    @Operation(summary = "Endpoint 일시 정지 (PAUSED — 새 delivery 안 만들어짐)")
    fun pause(@PathVariable id: String): ResponseEntity<Void> {
        lifecycle.pause(WebhookEndpointId.of(id))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/endpoints/{id}/resume")
    @Operation(summary = "Endpoint 재개")
    fun resume(@PathVariable id: String): ResponseEntity<Void> {
        lifecycle.resume(WebhookEndpointId.of(id))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/endpoints/{id}/rotate-secret")
    @Operation(summary = "Secret 갱신 — 새 평문이 응답에 한 번만 노출")
    fun rotateSecret(@PathVariable id: String): ResponseEntity<RotateSecretResponse> {
        val newSecret = lifecycle.rotateSecret(WebhookEndpointId.of(id))
        return ResponseEntity.ok(RotateSecretResponse(id = id, secret = newSecret))
    }

    // ─── Deliveries ──────────────────────────────────────────

    @GetMapping("/deliveries")
    @Operation(summary = "Endpoint 의 delivery 이력 (status 필터 가능)")
    fun listDeliveries(
        @RequestParam(required = false) endpointId: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "100") limit: Int,
    ): ResponseEntity<WebhookDeliveryListResponse> {
        val items = when {
            endpointId != null -> deliveries.findByEndpoint(WebhookEndpointId.of(endpointId), limit)
            status != null -> deliveries.findByStatus(WebhookDeliveryStatus.valueOf(status), limit)
            else -> emptyList()  // 둘 다 없으면 빈 응답 — 위험한 전체 스캔 회피
        }.map(::toDeliveryView)
        return ResponseEntity.ok(WebhookDeliveryListResponse(items = items))
    }

    @PostMapping("/deliveries/{id}/replay")
    @Operation(summary = "Dead-lettered delivery 수동 재시도 (큐에 다시 넣음)")
    fun replay(@PathVariable id: String): ResponseEntity<Void> {
        replayDelivery.replay(WebhookDeliveryId.of(id))
        return ResponseEntity.accepted().build()
    }

    // ─── DTO 변환 ─────────────────────────────────────────────

    private fun toEndpointView(e: WebhookEndpoint): WebhookEndpointView = WebhookEndpointView(
        id = e.id().toString(),
        customerId = e.customerId().value(),
        url = e.url(),
        subscribedEventTypes = e.subscribedEventTypes(),
        status = e.status().name,
        createdAt = e.createdAt().toString(),
    )

    private fun toDeliveryView(d: WebhookDelivery): WebhookDeliveryView = WebhookDeliveryView(
        id = d.id().toString(),
        endpointId = d.endpointId().toString(),
        eventType = d.eventType(),
        status = d.status().name,
        attemptCount = d.attemptCount(),
        nextAttemptAt = d.nextAttemptAt()?.toString(),
        lastResponseStatus = d.lastResponseStatus(),
        lastError = d.lastError(),
        createdAt = d.createdAt().toString(),
        deliveredAt = d.deliveredAt()?.toString(),
    )
}
