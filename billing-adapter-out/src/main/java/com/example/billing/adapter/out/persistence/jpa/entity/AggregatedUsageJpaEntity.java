package com.example.billing.adapter.out.persistence.jpa.entity;

import com.example.billing.domain.metering.ResourceType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "aggregated_usage", uniqueConstraints = {
        @UniqueConstraint(name = "uq_aggregated_customer_resource_period",
                columnNames = {"customer_id", "resource_type", "period_year_month"})
}, indexes = {
        @Index(name = "idx_aggregated_period", columnList = "period_year_month")
})
@Getter
@Setter
@NoArgsConstructor
public class AggregatedUsageJpaEntity {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 32)
    private ResourceType resourceType;

    @Column(name = "period_year_month", nullable = false, length = 7)
    private String periodYearMonth;  // "2026-05"

    @Column(name = "total_quantity", nullable = false)
    private long totalQuantity;

    @Column(name = "event_count", nullable = false)
    private long eventCount;

    @Column(name = "aggregated_at", nullable = false)
    private Instant aggregatedAt;
}
