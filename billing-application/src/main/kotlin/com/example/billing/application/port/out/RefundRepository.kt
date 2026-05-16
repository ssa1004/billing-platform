package com.example.billing.application.port.out

import com.example.billing.domain.refund.Refund
import com.example.billing.domain.refund.RefundId
import java.time.Instant
import java.util.Optional

interface RefundRepository {
    fun save(refund: Refund)
    fun findById(id: RefundId): Optional<Refund>

    /**
     * Reconciler 가 호출. `requestedAt <= staleBefore` 인 REQUESTED Refund 들.
     * 3-phase 흐름의 phase 3 (DB tx2) 가 깨졌을 가능성이 있는 후보. limit 만큼만.
     */
    fun findStaleRequested(staleBefore: Instant, limit: Int): List<Refund>

    /**
     * Soft delete (ADR-0030).
     *
     * @return 실제로 삭제된 row 가 있으면 true. 없거나 이미 삭제된 row 면 false.
     */
    fun softDelete(id: RefundId, deletedBy: String): Boolean

    /** 운영자 화면 전용. 삭제된 row 까지 조회. */
    fun findByIdIncludingDeleted(id: RefundId): Optional<Refund>
}
