package com.example.billing.adapter.out.persistence.jpa.repository

import com.example.billing.adapter.out.persistence.jpa.entity.RefundJpaEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.Optional
import java.util.UUID

interface SpringDataRefundRepository : JpaRepository<RefundJpaEntity, UUID> {

    /**
     * payment 에 status != :status(=FAILED) 인 활성 환불이 존재하는지.
     * 엔티티의 @SQLRestriction("deleted_at IS NULL") 로 삭제 row 는 자동 제외된다.
     */
    fun existsByPaymentIdAndStatusNot(paymentId: UUID, status: String): Boolean

    /**
     * Reconciler 가 호출. REQUESTED 로 stale 된 Refund 들을 오래된 순으로.
     * idempotencyKey 가 null 인 옛날 row 는 lookup 불가라 제외.
     */
    @Query(
        "SELECT r FROM RefundJpaEntity r " +
            "WHERE r.status = 'REQUESTED' AND r.requestedAt <= :before " +
            "AND r.idempotencyKey IS NOT NULL " +
            "ORDER BY r.requestedAt ASC",
    )
    fun findStaleRequested(@Param("before") before: Instant, pageable: Pageable): List<RefundJpaEntity>

    /** Soft delete (ADR-0030). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = "UPDATE refunds SET deleted_at = :now, deleted_by = :by " +
            "WHERE id = :id AND deleted_at IS NULL",
        nativeQuery = true,
    )
    fun softDelete(
        @Param("id") id: UUID,
        @Param("by") deletedBy: String,
        @Param("now") now: Instant,
    ): Int

    /** 운영자 화면 전용. 삭제된 row 까지 조회. */
    @Query(value = "SELECT * FROM refunds WHERE id = :id", nativeQuery = true)
    fun findByIdIncludingDeleted(@Param("id") id: UUID): Optional<RefundJpaEntity>
}
