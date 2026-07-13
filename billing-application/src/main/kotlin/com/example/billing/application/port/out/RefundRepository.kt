package com.example.billing.application.port.out

import com.example.billing.domain.payment.PaymentId
import com.example.billing.domain.refund.Refund
import com.example.billing.domain.refund.RefundId
import java.time.Instant
import java.util.Optional

interface RefundRepository {
    fun save(refund: Refund)
    fun findById(id: RefundId): Optional<Refund>

    /**
     * 해당 payment 에 FAILED 가 아닌(활성) 환불이 이미 있으면 true.
     * 서로 다른 Idempotency-Key 로 같은 결제를 두 번 환불하는 이중 지급을 막는 선검사.
     * DB 의 부분 유니크 인덱스(postgres)가 동시 요청의 최종 방어선.
     */
    fun existsActiveByPaymentId(paymentId: PaymentId): Boolean

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
