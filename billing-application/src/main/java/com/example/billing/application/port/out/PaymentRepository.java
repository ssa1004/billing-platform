package com.example.billing.application.port.out;

import com.example.billing.domain.payment.Payment;
import com.example.billing.domain.payment.PaymentId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository {
    void save(Payment payment);
    Optional<Payment> findById(PaymentId id);
    Optional<Payment> findByIdempotencyKey(String key);

    /**
     * Reconciler 가 호출. {@code createdAt <= staleBefore} 인 PENDING Payment 들.
     * 3-phase 흐름의 phase 3 (DB tx2) 가 깨졌을 가능성이 있는 후보. limit 만큼만.
     * staleBefore 는 일반적인 PG 호출 + tx2 시간보다 충분히 큰 값 (예: 5분 전) 으로 호출.
     */
    List<Payment> findStalePending(Instant staleBefore, int limit);

    /**
     * Soft delete (ADR-0030). PG 매칭 row 라 물리 삭제 절대 금지 — UPDATE 만.
     *
     * @return 실제로 삭제된 row 가 있으면 true. 없거나 이미 삭제된 row 면 false.
     */
    boolean softDelete(PaymentId id, String deletedBy);

    /** 운영자 화면 전용. 삭제된 row 까지 조회. */
    Optional<Payment> findByIdIncludingDeleted(PaymentId id);
}
