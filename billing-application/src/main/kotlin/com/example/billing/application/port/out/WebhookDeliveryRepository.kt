package com.example.billing.application.port.out

import com.example.billing.domain.webhook.WebhookDelivery
import com.example.billing.domain.webhook.WebhookDeliveryId
import com.example.billing.domain.webhook.WebhookDeliveryStatus
import com.example.billing.domain.webhook.WebhookEndpointId
import java.time.Instant
import java.util.Optional

interface WebhookDeliveryRepository {

    fun save(delivery: WebhookDelivery)

    fun findById(id: WebhookDeliveryId): Optional<WebhookDelivery>

    /**
     * 워커가 매 분 호출 — PENDING 이면서 next_attempt_at 도달한 것을 SKIP LOCKED 로 잠금.
     * 같은 트랜잭션 안에서 처리해야 lock 이 유지됨.
     */
    fun claimPending(now: Instant, limit: Int): List<WebhookDelivery>

    /** 운영 화면 — 한 endpoint 의 delivery timeline. */
    fun findByEndpoint(endpointId: WebhookEndpointId, limit: Int): List<WebhookDelivery>

    /** Dead letter 화면. */
    fun findByStatus(status: WebhookDeliveryStatus, limit: Int): List<WebhookDelivery>
}
