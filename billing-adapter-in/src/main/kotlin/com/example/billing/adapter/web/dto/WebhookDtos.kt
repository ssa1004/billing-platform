package com.example.billing.adapter.web.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

data class RegisterWebhookEndpointRequest(
    @field:NotBlank val customerId: String,
    @field:NotBlank
    @field:Pattern(regexp = "^https://.+|^http://localhost.*|^http://127\\.0\\.0\\.1.*",
                   message = "url must be https (http://localhost only for dev)")
    val url: String,
    /** 비어있으면 모든 이벤트 구독. 명시하면 그 타입들만. */
    val subscribedEventTypes: Set<String> = emptySet(),
)

/**
 * 등록 직후 응답 — 평문 secret 이 *이 응답 1번* 만 노출. customer 는 이 값을 자기 검증
 * 코드에 즉시 반영해야. 분실 시 rotate-secret 으로 갱신.
 */
data class RegisterWebhookEndpointResponse(
    val id: String,
    val customerId: String,
    val url: String,
    val secret: String,                    // 한 번만 노출
    val subscribedEventTypes: Set<String>,
    val status: String,
)

/** 이후 조회 응답 — secret 은 *반환 안 함*. */
data class WebhookEndpointView(
    val id: String,
    val customerId: String,
    val url: String,
    val subscribedEventTypes: Set<String>,
    val status: String,
    val createdAt: String,
)

data class WebhookEndpointListResponse(val items: List<WebhookEndpointView>)

data class WebhookDeliveryView(
    val id: String,
    val endpointId: String,
    val eventType: String,
    val status: String,
    val attemptCount: Int,
    val nextAttemptAt: String?,
    val lastResponseStatus: Int?,
    val lastError: String?,
    val createdAt: String,
    val deliveredAt: String?,
)

data class WebhookDeliveryListResponse(val items: List<WebhookDeliveryView>)

data class RotateSecretResponse(
    val id: String,
    val secret: String,                   // 새 평문 secret. 한 번만 노출.
)
