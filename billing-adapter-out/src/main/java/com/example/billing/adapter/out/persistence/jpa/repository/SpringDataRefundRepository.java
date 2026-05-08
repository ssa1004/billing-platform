package com.example.billing.adapter.out.persistence.jpa.repository;

import com.example.billing.adapter.out.persistence.jpa.entity.RefundJpaEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringDataRefundRepository extends JpaRepository<RefundJpaEntity, UUID> {

    /**
     * Reconciler 가 호출. REQUESTED 로 stale 된 Refund 들을 오래된 순으로.
     * idempotencyKey 가 null 인 옛날 row 는 lookup 불가라 제외.
     */
    @Query("SELECT r FROM RefundJpaEntity r "
            + "WHERE r.status = 'REQUESTED' AND r.requestedAt <= :before "
            + "AND r.idempotencyKey IS NOT NULL "
            + "ORDER BY r.requestedAt ASC")
    List<RefundJpaEntity> findStaleRequested(@Param("before") Instant before, Pageable pageable);
}
