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

    /** Lombok `@AllArgsConstructor` 호환. */
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

    companion object {
        /** Lombok `@Builder` 호환 — 기존 코드 (OutboxEventPublisher) 가 builder() 사용. */
        @JvmStatic
        fun builder(): OutboxJpaEntityBuilder = OutboxJpaEntityBuilder()
    }

    /** Lombok 이 자동 생성하던 builder 와 동일 시그니처. Group 6 에서 OutboxEventPublisher 가 Kotlin 으로 가면 제거 가능. */
    class OutboxJpaEntityBuilder {
        private var id: UUID? = null
        private var aggregateType: String = ""
        private var aggregateId: String = ""
        private var eventType: String = ""
        private var payload: String = ""
        private var createdAt: Instant = Instant.EPOCH
        private var publishedAt: Instant? = null

        fun id(v: UUID?): OutboxJpaEntityBuilder = apply { id = v }
        fun aggregateType(v: String): OutboxJpaEntityBuilder = apply { aggregateType = v }
        fun aggregateId(v: String): OutboxJpaEntityBuilder = apply { aggregateId = v }
        fun eventType(v: String): OutboxJpaEntityBuilder = apply { eventType = v }
        fun payload(v: String): OutboxJpaEntityBuilder = apply { payload = v }
        fun createdAt(v: Instant): OutboxJpaEntityBuilder = apply { createdAt = v }
        fun publishedAt(v: Instant?): OutboxJpaEntityBuilder = apply { publishedAt = v }

        fun build(): OutboxJpaEntity = OutboxJpaEntity(
            id, aggregateType, aggregateId, eventType, payload, createdAt, publishedAt,
        )
    }
}
