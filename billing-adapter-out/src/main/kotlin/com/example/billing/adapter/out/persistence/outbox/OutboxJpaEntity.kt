package com.example.billing.adapter.out.persistence.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "outbox")
class OutboxJpaEntity() {

    @Id
    @Column(name = "id")
    var id: UUID? = null

    @Column(name = "aggregate_type", nullable = false, length = 64)
    var aggregateType: String = ""

    @Column(name = "aggregate_id", nullable = false, length = 128)
    var aggregateId: String = ""

    @Column(name = "event_type", nullable = false, length = 64)
    var eventType: String = ""

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    var payload: String = ""

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH

    @Column(name = "published_at")
    var publishedAt: Instant? = null

    /** all-args secondary constructor — OutboxEventPublisher 가 위치 인자로 생성. */
    constructor(
        id: UUID?,
        aggregateType: String,
        aggregateId: String,
        eventType: String,
        payload: String,
        createdAt: Instant,
        publishedAt: Instant?,
    ) : this() {
        this.id = id
        this.aggregateType = aggregateType
        this.aggregateId = aggregateId
        this.eventType = eventType
        this.payload = payload
        this.createdAt = createdAt
        this.publishedAt = publishedAt
    }
}
