package com.example.billing.adapter.out.persistence.jpa.repository

import com.example.billing.adapter.out.persistence.jpa.entity.SettlementRunJpaEntity
import com.example.billing.domain.settlement.SettlementStatus
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.data.repository.query.Param
import java.util.UUID

interface SpringDataSettlementRunRepository : JpaRepository<SettlementRunJpaEntity, UUID> {

    fun findByPeriodYearMonth(periodYearMonth: String): List<SettlementRunJpaEntity>

    /**
     * PENDING SettlementRun 을 SKIP LOCKED 로 잡아옴. worker pool 의 핵심.
     * 같은 트랜잭션에서 처리해야 lock 효과 유지.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(
        QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"),  // SKIP_LOCKED
    )
    @Query(
        """
        SELECT s FROM SettlementRunJpaEntity s
         WHERE s.periodYearMonth = :period
           AND s.status = :status
         ORDER BY s.createdAt
        """,
    )
    fun claimPendingForUpdate(
        @Param("period") period: String,
        @Param("status") status: SettlementStatus,
        pageable: Pageable,
    ): List<SettlementRunJpaEntity>
}
