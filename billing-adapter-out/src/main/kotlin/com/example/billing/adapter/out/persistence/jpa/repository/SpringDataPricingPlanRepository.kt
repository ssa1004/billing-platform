package com.example.billing.adapter.out.persistence.jpa.repository

import com.example.billing.adapter.out.persistence.jpa.entity.PricingPlanJpaEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.Optional
import java.util.UUID

interface SpringDataPricingPlanRepository : JpaRepository<PricingPlanJpaEntity, UUID> {

    /** 주어진 시점에 customer 에게 적용되는 가장 최근 plan. customer 별 정의가 없으면 default. */
    @Query(
        """
        SELECT p FROM PricingPlanJpaEntity p
         WHERE (p.customerId = :customerId OR p.customerId IS NULL)
           AND p.effectiveFrom <= :at
         ORDER BY (CASE WHEN p.customerId = :customerId THEN 0 ELSE 1 END), p.effectiveFrom DESC
        """,
    )
    fun findEffectiveCandidates(
        @Param("customerId") customerId: String,
        @Param("at") at: Instant,
    ): List<PricingPlanJpaEntity>

    fun findEffective(customerId: String, at: Instant): Optional<PricingPlanJpaEntity> =
        findEffectiveCandidates(customerId, at).stream().findFirst()
}
