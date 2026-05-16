package com.example.billing.application.command

/**
 * Customer 가 webhook 수신 endpoint 를 등록.
 *
 * [subscribedEventTypes] 가 비어 있으면 모든 이벤트 구독 (default).
 * 비추: 처음 통합하는 customer 는 보통 모든 이벤트로 시작 → 익숙해지면 선별.
 */
@JvmRecord
data class RegisterWebhookEndpointCommand(
    val idempotencyKey: String,
    val customerId: String,
    val url: String,
    val subscribedEventTypes: Set<String>,
)
