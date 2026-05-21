package com.example.billing.adapter.out.persistence.jpa.entity

import com.example.billing.domain.webhook.WebhookEndpointStatus
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
@Table(name = "webhook_endpoints")
class WebhookEndpointJpaEntity() {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null

    @Column(name = "customer_id", nullable = false, length = 64)
    var customerId: String = ""

    @Column(name = "url", nullable = false, length = 2048)
    var url: String = ""

    @Column(name = "secret", nullable = false, length = 128)
    var secret: String = ""

    /**
     * Rotation grace 동안 함께 유효한 직전 secret. null = grace 밖 (rotate 안 했거나 만료 후 정리).
     * ADR-0029.
     */
    @Column(name = "previous_secret", length = 128)
    var previousSecret: String? = null

    /** previousSecret 만료 시각. previousSecret 과 항상 짝 (둘 다 null 이거나 둘 다 set). */
    @Column(name = "previous_secret_valid_until")
    var previousSecretValidUntil: Instant? = null

    /** 구독 이벤트 타입의 JSON 배열 직렬화. mapper 가 Set<String> 로 변환. */
    @Column(name = "subscribed_event_types_json", nullable = false, columnDefinition = "TEXT")
    var subscribedEventTypesJson: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: WebhookEndpointStatus = WebhookEndpointStatus.ACTIVE

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

    /** 전체 필드 positional 생성자 — mapper 가 positional 로 생성. */
    constructor(
        id: UUID?,
        customerId: String,
        url: String,
        secret: String,
        previousSecret: String?,
        previousSecretValidUntil: Instant?,
        subscribedEventTypesJson: String,
        status: WebhookEndpointStatus,
        createdAt: Instant,
        updatedAt: Instant,
        version: Long,
    ) : this() {
        this.id = id
        this.customerId = customerId
        this.url = url
        this.secret = secret
        this.previousSecret = previousSecret
        this.previousSecretValidUntil = previousSecretValidUntil
        this.subscribedEventTypesJson = subscribedEventTypesJson
        this.status = status
        this.createdAt = createdAt
        this.updatedAt = updatedAt
        this.version = version
    }
}
