package com.example.billing.adapter.out.persistence.jpa.repository;

import com.example.billing.adapter.out.persistence.jpa.entity.CreditJpaEntity;
import com.example.billing.domain.credit.CreditStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringDataCreditRepository extends JpaRepository<CreditJpaEntity, UUID> {

    /** 차감 우선순위: 만료 임박 → 발급 시점 빠른 것. PROMO/COMPENSATION 우선 소진. */
    @Query("""
            SELECT c FROM CreditJpaEntity c
             WHERE c.customerId = :customerId
               AND c.status = com.example.billing.domain.credit.CreditStatus.ACTIVE
               AND c.balance > 0
               AND c.validFrom <= :now
               AND (c.validUntil IS NULL OR c.validUntil > :now)
             ORDER BY
                CASE WHEN c.validUntil IS NULL THEN 1 ELSE 0 END,
                c.validUntil ASC NULLS LAST,
                c.createdAt ASC
            """)
    List<CreditJpaEntity> findUsable(@Param("customerId") String customerId,
                                     @Param("now") Instant now);

    /** 만료 batch: ACTIVE 이면서 valid_until 이 now 이전인 것들. */
    @Query("""
            SELECT c FROM CreditJpaEntity c
             WHERE c.status = com.example.billing.domain.credit.CreditStatus.ACTIVE
               AND c.validUntil IS NOT NULL
               AND c.validUntil <= :now
             ORDER BY c.validUntil ASC
            """)
    List<CreditJpaEntity> findExpiredCandidates(@Param("now") Instant now,
                                                org.springframework.data.domain.Pageable pageable);

    /** [now, until] 사이에 만료될 ACTIVE — 알림 / 운영 화면. */
    @Query("""
            SELECT c FROM CreditJpaEntity c
             WHERE c.customerId = :customerId
               AND c.status = com.example.billing.domain.credit.CreditStatus.ACTIVE
               AND c.balance > 0
               AND c.validUntil IS NOT NULL
               AND c.validUntil > :now
               AND c.validUntil <= :until
             ORDER BY c.validUntil ASC
            """)
    List<CreditJpaEntity> findExpiringSoon(@Param("customerId") String customerId,
                                           @Param("now") Instant now,
                                           @Param("until") Instant until);

    /** 고객의 모든 Credit, 최근 발급 우선. status 무관. */
    List<CreditJpaEntity> findByCustomerIdOrderByCreatedAtDesc(
            String customerId,
            org.springframework.data.domain.Pageable pageable);
}
