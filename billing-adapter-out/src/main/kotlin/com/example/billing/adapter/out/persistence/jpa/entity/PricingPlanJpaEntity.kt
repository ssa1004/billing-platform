package com.example.billing.adapter.out.persistence.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 가격 정책 행. tiers 는 JSON column 으로 직렬화 (PostgreSQL jsonb 호환).
 *
 * customer_id 가 null 인 행은 default plan. customer_id 가 set 된 행은 customer-specific
 * 오버라이드.
 */
@Entity
@Table(
    name = "pricing_plans",
    indexes = [
        Index(name = "idx_pricing_customer_effective", columnList = "customer_id, effective_from"),
    ],
)
class PricingPlanJpaEntity {

    @Id
    var id: UUID? = null

    @Column(name = "customer_id", length = 64)
    var customerId: String? = null  // null = default plan

    @Column(name = "name", nullable = false, length = 64)
    var name: String = ""

    /** Tier 리스트의 JSON 직렬화 (jackson). */
    @Column(name = "tiers_json", nullable = false, columnDefinition = "TEXT")
    var tiersJson: String = ""

    @Column(name = "effective_from", nullable = false)
    var effectiveFrom: Instant = Instant.EPOCH
}
