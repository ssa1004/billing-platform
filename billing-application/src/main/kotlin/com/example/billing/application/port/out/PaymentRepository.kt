package com.example.billing.application.port.out

import com.example.billing.domain.payment.Payment
import com.example.billing.domain.payment.PaymentId
import java.time.Instant
import java.util.Optional

interface PaymentRepository {
    fun save(payment: Payment)
    fun findById(id: PaymentId): Optional<Payment>
    fun findByIdempotencyKey(key: String): Optional<Payment>

    /**
     * Reconciler 가 호출. `createdAt <= staleBefore` 인 PENDING Payment 들.
     * 3-phase 흐름의 phase 3 (DB tx2) 가 깨졌을 가능성이 있는 후보. limit 만큼만.
     * staleBefore 는 일반적인 PG 호출 + tx2 시간보다 충분히 큰 값 (예: 5분 전) 으로 호출.
     */
    fun findStalePending(staleBefore: Instant, limit: Int): List<Payment>

    /**
     * Soft delete (ADR-0030). PG 매칭 row 라 물리 삭제 절대 금지 — UPDATE 만.
     *
     * @return 실제로 삭제된 row 가 있으면 true. 없거나 이미 삭제된 row 면 false.
     */
    fun softDelete(id: PaymentId, deletedBy: String): Boolean

    /** 운영자 화면 전용. 삭제된 row 까지 조회. */
    fun findByIdIncludingDeleted(id: PaymentId): Optional<Payment>
}
