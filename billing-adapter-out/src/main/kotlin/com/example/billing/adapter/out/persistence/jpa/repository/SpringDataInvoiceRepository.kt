package com.example.billing.adapter.out.persistence.jpa.repository

import com.example.billing.adapter.out.persistence.jpa.entity.InvoiceJpaEntity
import com.example.billing.domain.invoice.InvoiceStatus
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.Optional
import java.util.UUID

interface SpringDataInvoiceRepository :
    JpaRepository<InvoiceJpaEntity, UUID>,
    SpringDataInvoiceRepositoryAged {

    fun findByCustomerIdAndPeriodYearMonth(
        customerId: String,
        periodYearMonth: String,
    ): Optional<InvoiceJpaEntity>

    fun findByCustomerIdOrderByPeriodYearMonthDesc(
        customerId: String,
        pageable: Pageable,
    ): List<InvoiceJpaEntity>

    /**
     * 결제 재시도 후보를 가져옴. SKIP LOCKED 로 worker pool 이 같은 invoice 를 두 번 잡지
     * 않도록 보장. PostgreSQL 전용 (H2 는 SKIP LOCKED 미지원이므로 dev 에선 fallback).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(
        QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"),  // SKIP_LOCKED hint
    )
    @Query("SELECT i FROM InvoiceJpaEntity i WHERE i.status = :status ORDER BY i.dueAt")
    fun findForRetryWithLock(
        @Param("status") status: InvoiceStatus,
        pageable: Pageable,
    ): List<InvoiceJpaEntity>

    /**
     * Soft delete (ADR-0030) — UPDATE 1번으로 deleted_at + deleted_by 동시 마킹. 이미 삭제된
     * row 는 (deleted_at IS NULL 절 때문에) 영향 X — 멱등.
     *
     * NativeQuery 인 이유: [org.hibernate.annotations.SQLRestriction] 이 JPQL 에는 자동으로
     * deleted_at IS NULL 을 끼우지만 본 UPDATE 가 이미 그 컬럼을 다루므로 NativeQuery 가 의도가
     * 명확. 또한 deleted_by 를 동시에 채워야 하는 요구를 `@SQLDelete` 만으론 못 함.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = "UPDATE invoices SET deleted_at = :now, deleted_by = :by " +
            "WHERE id = :id AND deleted_at IS NULL",
        nativeQuery = true,
    )
    fun softDelete(
        @Param("id") id: UUID,
        @Param("by") deletedBy: String,
        @Param("now") now: Instant,
    ): Int

    /**
     * 운영자 화면 전용. SQLRestriction 우회를 위한 NativeQuery.
     * 일반 도메인 흐름에서 호출 금지 — 활성 row 만 본다는 기본 가정 을 깸.
     */
    @Query(value = "SELECT * FROM invoices WHERE id = :id", nativeQuery = true)
    fun findByIdIncludingDeleted(@Param("id") id: UUID): Optional<InvoiceJpaEntity>
}
