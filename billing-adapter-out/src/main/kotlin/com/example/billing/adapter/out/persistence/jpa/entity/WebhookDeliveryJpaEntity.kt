package com.example.billing.adapter.out.persistence.jpa.entity

import com.example.billing.domain.webhook.WebhookDeliveryStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "webhook_deliveries")
class WebhookDeliveryJpaEntity() {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null

    @Column(name = "endpoint_id", nullable = false)
    var endpointId: UUID? = null

    @Column(name = "event_type", nullable = false, length = 64)
    var eventType: String = ""

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    var payload: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: WebhookDeliveryStatus = WebhookDeliveryStatus.PENDING

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0

    @Column(name = "next_attempt_at")
    var nextAttemptAt: Instant? = null

    @Column(name = "last_response_status")
    var lastResponseStatus: Int? = null

    @Column(name = "last_error", length = 256)
    var lastError: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH

    @Column(name = "delivered_at")
    var deliveredAt: Instant? = null

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

    /** Lombok `@AllArgsConstructor` 호환 — mapper 가 positional 로 생성. */
    constructor(
        id: UUID?,
        endpointId: UUID?,
        eventType: String,
        payload: String,
        status: WebhookDeliveryStatus,
        attemptCount: Int,
        nextAttemptAt: Instant?,
        lastResponseStatus: Int?,
        lastError: String?,
        createdAt: Instant,
        updatedAt: Instant,
        deliveredAt: Instant?,
        version: Long,
    ) : this() {
        this.id = id
        this.endpointId = endpointId
        this.eventType = eventType
        this.payload = payload
        this.status = status
        this.attemptCount = attemptCount
        this.nextAttemptAt = nextAttemptAt
        this.lastResponseStatus = lastResponseStatus
        this.lastError = lastError
        this.createdAt = createdAt
        this.updatedAt = updatedAt
        this.deliveredAt = deliveredAt
        this.version = version
    }
}
