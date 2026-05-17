package com.example.billing.adapter.out.persistence.jpa.entity

import com.example.billing.domain.metering.ResourceType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "aggregated_usage",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_aggregated_customer_resource_period",
            columnNames = ["customer_id", "resource_type", "period_year_month"],
        ),
    ],
    indexes = [
        Index(name = "idx_aggregated_period", columnList = "period_year_month"),
    ],
)
class AggregatedUsageJpaEntity {

    @Id
    var id: UUID? = null

    @Column(name = "customer_id", nullable = false, length = 64)
    var customerId: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 32)
    var resourceType: ResourceType = ResourceType.API_CALL

    @Column(name = "period_year_month", nullable = false, length = 7)
    var periodYearMonth: String = ""  // "2026-05"

    @Column(name = "total_quantity", nullable = false)
    var totalQuantity: Long = 0

    @Column(name = "event_count", nullable = false)
    var eventCount: Long = 0

    @Column(name = "aggregated_at", nullable = false)
    var aggregatedAt: Instant = Instant.EPOCH
}
