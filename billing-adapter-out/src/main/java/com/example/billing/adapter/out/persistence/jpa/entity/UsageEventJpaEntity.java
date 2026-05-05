package com.example.billing.adapter.out.persistence.jpa.entity;

import com.example.billing.domain.metering.ResourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * UsageEvent 행. eventId 가 PK 이자 멱등성 키.
 *
 * <p>인덱스 (customer_id, resource_type, occurred_at) 가 월별 집계 query 의 핵심 경로.</p>
 */
@Entity
@Table(name = "usage_events", indexes = {
        @Index(name = "idx_usage_customer_resource_occurred",
                columnList = "customer_id, resource_type, occurred_at"),
        @Index(name = "idx_usage_received_at", columnList = "received_at")
})
@Getter
@Setter
@NoArgsConstructor
public class UsageEventJpaEntity {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 32)
    private ResourceType resourceType;

    @Column(name = "quantity", nullable = false)
    private long quantity;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}
