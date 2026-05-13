package com.example.billing.adapter.web

import com.example.billing.adapter.web.auth.Caller
import com.example.billing.adapter.web.dto.RegisterWebhookEndpointRequest
import com.example.billing.adapter.web.dto.RegisterWebhookEndpointResponse
import com.example.billing.adapter.web.dto.RotateSecretResponse
import com.example.billing.adapter.web.dto.WebhookDeliveryListResponse
import com.example.billing.adapter.web.dto.WebhookDeliveryView
import com.example.billing.adapter.web.dto.WebhookEndpointListResponse
import com.example.billing.adapter.web.dto.WebhookEndpointView
import com.example.billing.application.command.RegisterWebhookEndpointCommand
import com.example.billing.application.exception.WebhookEndpointNotFoundException
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
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import kotlin.jvm.optionals.getOrNull

/**
 * Webhook 운영 API. (Webhook = 우리가 customer 서버 URL 로 HTTP POST 를 쏴서 알리는 push 통신)
 *
 * <p>두 갈래:
 * <ul>
 *   <li>Endpoint CRUD — customer 가 자기 수신 URL 을 등록/관리</li>
 *   <li>Delivery 조회 / replay — 발송 이력 조회 + dead letter (영구 실패) 수동 재시도</li>
 * </ul>
 *
 * <p><b>OWASP API1 BOLA</b>: endpoint / delivery 모두 endpoint.customerId 기준으로 owner
 * 검사. customer 가 다른 customer 의 endpoint 를 pause / replay 하는 사고 방지.</p>
 *
 * <p><b>OWASP API10 — SSRF</b>: 등록 시 [com.example.billing.domain.webhook.WebhookEndpoint.register]
 * 가 도메인 invariant 로 URL scheme + host 검사 (loopback / link-local / private CIDR /
 * cloud metadata IP 차단). 본 controller 는 URL 자체를 손대지 않음.</p>
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
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<RegisterWebhookEndpointResponse> {
        Caller.from(jwt).requireOwnerOrAdmin(req.customerId)
        val cmd = RegisterWebhookEndpointCommand(
            idempotencyKey,
            req.customerId,
            req.url,
            req.subscribedEventTypes,
        )
        val endpoint = registerEndpoint.register(cmd)
        return ResponseEntity.ok(
            RegisterWebhookEndpointResponse(
                id = endpoint.id.toString(),
                customerId = endpoint.customerId.value,
                url = endpoint.url,
                secret = endpoint.secret,
                subscribedEventTypes = endpoint.subscribedEventTypes(),
                status = endpoint.status.name,
            )
        )
    }

    @GetMapping("/endpoints")
    @Operation(summary = "Customer 의 endpoint 목록 (secret 미포함)")
    fun listEndpoints(
        @RequestParam customerId: String,
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<WebhookEndpointListResponse> {
        Caller.from(jwt).requireOwnerOrAdmin(customerId)
        val items = endpoints.findByCustomer(CustomerId.of(customerId)).map(::toEndpointView)
        return ResponseEntity.ok(WebhookEndpointListResponse(items = items))
    }

    @PostMapping("/endpoints/{id}/pause")
    @Operation(summary = "Endpoint 일시 정지 (PAUSED — 새 delivery 안 만들어짐)")
    fun pause(
        @PathVariable id: String,
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<Void> {
        authorizeEndpoint(id, jwt)
        lifecycle.pause(WebhookEndpointId.of(id))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/endpoints/{id}/resume")
    @Operation(summary = "Endpoint 재개")
    fun resume(
        @PathVariable id: String,
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<Void> {
        authorizeEndpoint(id, jwt)
        lifecycle.resume(WebhookEndpointId.of(id))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/endpoints/{id}/rotate-secret")
    @Operation(summary = "Secret 갱신 — 새 평문이 응답에 한 번만 노출")
    fun rotateSecret(
        @PathVariable id: String,
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<RotateSecretResponse> {
        authorizeEndpoint(id, jwt)
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
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<WebhookDeliveryListResponse> {
        val bounded = limit.coerceIn(1, MAX_LIMIT)
        val caller = Caller.from(jwt)
        val items = when {
            endpointId != null -> {
                authorizeEndpoint(endpointId, jwt)
                deliveries.findByEndpoint(WebhookEndpointId.of(endpointId), bounded)
            }
            // status-only 조회는 customer 자원 격리가 불가능하다 (status 가 customer 와 무관) —
            // 운영자 전용으로만 허용.
            status != null -> {
                if (!caller.isAdmin) {
                    throw org.springframework.security.access.AccessDeniedException(
                        "status-only delivery query is admin-only",
                    )
                }
                deliveries.findByStatus(WebhookDeliveryStatus.valueOf(status), bounded)
            }
            // 필터가 둘 다 없으면 빈 응답 — 모든 row 를 가져오는 위험한 전체 스캔 방지
            else -> emptyList()
        }.map(::toDeliveryView)
        return ResponseEntity.ok(WebhookDeliveryListResponse(items = items))
    }

    @PostMapping("/deliveries/{id}/replay")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "Dead-lettered delivery 수동 재시도 (큐에 다시 넣음) — 운영자 전용")
    fun replay(@PathVariable id: String): ResponseEntity<Void> {
        replayDelivery.replay(WebhookDeliveryId.of(id))
        return ResponseEntity.accepted().build()
    }

    // ─── helper ──────────────────────────────────────────────

    /**
     * endpoint id 만 노출된 endpoint 의 ownership 검사 — endpoint 를 한 번 로드해 customer
     * 를 추출한 뒤 caller 와 매칭. admin 은 통과.
     */
    private fun authorizeEndpoint(id: String, jwt: Jwt?) {
        val endpoint = endpoints.findById(WebhookEndpointId.of(id)).getOrNull()
            ?: throw WebhookEndpointNotFoundException(WebhookEndpointId.of(id))
        Caller.from(jwt).requireOwnerOrAdmin(endpoint.customerId.value)
    }

    private fun toEndpointView(e: WebhookEndpoint): WebhookEndpointView = WebhookEndpointView(
        id = e.id.toString(),
        customerId = e.customerId.value,
        url = e.url,
        subscribedEventTypes = e.subscribedEventTypes(),
        status = e.status.name,
        createdAt = e.createdAt.toString(),
    )

    private fun toDeliveryView(d: WebhookDelivery): WebhookDeliveryView = WebhookDeliveryView(
        id = d.id.toString(),
        endpointId = d.endpointId.toString(),
        eventType = d.eventType,
        status = d.status.name,
        attemptCount = d.attemptCount,
        nextAttemptAt = d.nextAttemptAt?.toString(),
        lastResponseStatus = d.lastResponseStatus,
        lastError = d.lastError,
        createdAt = d.createdAt.toString(),
        deliveredAt = d.deliveredAt?.toString(),
    )

    companion object {
        /** OWASP API4 — Unrestricted Resource Consumption cap. */
        private const val MAX_LIMIT = 200
    }
}
