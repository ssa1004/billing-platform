package com.example.billing.adapter.out.persistence.jpa.entity

import com.example.billing.domain.metering.ResourceType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * UsageEvent 행. eventId 가 PK 이자 멱등성 키.
 *
 * 인덱스 (customer_id, resource_type, occurred_at) 가 월별 집계 query 의 핵심 경로.
 */
@Entity
@Table(
    name = "usage_events",
    indexes = [
        Index(
            name = "idx_usage_customer_resource_occurred",
            columnList = "customer_id, resource_type, occurred_at",
        ),
        Index(name = "idx_usage_received_at", columnList = "received_at"),
    ],
)
class UsageEventJpaEntity {

    @Id
    @Column(name = "event_id")
    var eventId: UUID? = null

    @Column(name = "customer_id", nullable = false, length = 64)
    var customerId: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 32)
    var resourceType: ResourceType = ResourceType.API_CALL

    @Column(name = "quantity", nullable = false)
    var quantity: Long = 0

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant = Instant.EPOCH

    @Column(name = "received_at", nullable = false)
    var receivedAt: Instant = Instant.EPOCH
}
