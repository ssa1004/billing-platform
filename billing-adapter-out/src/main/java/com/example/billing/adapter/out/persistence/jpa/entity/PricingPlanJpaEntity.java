package com.example.billing.adapter.out.persistence.jpa.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * 가격 정책 행. tiers 는 JSON column 으로 직렬화 (PostgreSQL jsonb 호환).
 *
 * <p>customer_id 가 null 인 행은 default plan. customer_id 가 set 된 행은 customer-specific
 * 오버라이드.</p>
 */
@Entity
@Table(name = "pricing_plans", indexes = {
        @Index(name = "idx_pricing_customer_effective",
                columnList = "customer_id, effective_from")
})
@Getter
@Setter
@NoArgsConstructor
public class PricingPlanJpaEntity {

    @Id
    private UUID id;

    @Column(name = "customer_id", length = 64)
    private String customerId;  // null = default plan

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** Tier 리스트의 JSON 직렬화 (jackson). */
    @Column(name = "tiers_json", nullable = false, columnDefinition = "TEXT")
    private String tiersJson;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;
}
