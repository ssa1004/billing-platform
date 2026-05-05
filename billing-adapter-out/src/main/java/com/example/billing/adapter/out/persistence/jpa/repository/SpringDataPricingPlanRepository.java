package com.example.billing.adapter.out.persistence.jpa.repository;

import com.example.billing.adapter.out.persistence.jpa.entity.PricingPlanJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataPricingPlanRepository extends JpaRepository<PricingPlanJpaEntity, UUID> {

    /** 주어진 시점에 customer 에게 적용되는 가장 최근 plan. customer 별 정의가 없으면 default. */
    @Query("""
            SELECT p FROM PricingPlanJpaEntity p
             WHERE (p.customerId = :customerId OR p.customerId IS NULL)
               AND p.effectiveFrom <= :at
             ORDER BY (CASE WHEN p.customerId = :customerId THEN 0 ELSE 1 END), p.effectiveFrom DESC
            """)
    java.util.List<PricingPlanJpaEntity> findEffectiveCandidates(
            @Param("customerId") String customerId,
            @Param("at") Instant at);

    default Optional<PricingPlanJpaEntity> findEffective(String customerId, Instant at) {
        return findEffectiveCandidates(customerId, at).stream().findFirst();
    }
}
