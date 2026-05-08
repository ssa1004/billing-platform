package com.example.billing.adapter.out.persistence.jpa.repository;

import com.example.billing.adapter.out.persistence.jpa.entity.PaymentJpaEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataPaymentRepository extends JpaRepository<PaymentJpaEntity, UUID> {
    Optional<PaymentJpaEntity> findByIdempotencyKey(String key);

    /**
     * Reconciler 가 호출. PENDING 으로 stale 된 Payment 들을 오래된 순으로.
     * (status, created_at) 인덱스를 사용한다.
     */
    @Query("SELECT p FROM PaymentJpaEntity p "
            + "WHERE p.status = 'PENDING' AND p.createdAt <= :before "
            + "ORDER BY p.createdAt ASC")
    List<PaymentJpaEntity> findStalePending(@Param("before") Instant before, Pageable pageable);

    /** Soft delete (ADR-0030). PG 매칭 row 라 물리 삭제 절대 금지. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE payments SET deleted_at = :now, deleted_by = :by "
            + "WHERE id = :id AND deleted_at IS NULL", nativeQuery = true)
    int softDelete(@Param("id") UUID id, @Param("by") String deletedBy, @Param("now") Instant now);

    /** 운영자 화면 전용. 삭제된 row 까지 조회. */
    @Query(value = "SELECT * FROM payments WHERE id = :id", nativeQuery = true)
    Optional<PaymentJpaEntity> findByIdIncludingDeleted(@Param("id") UUID id);
}
