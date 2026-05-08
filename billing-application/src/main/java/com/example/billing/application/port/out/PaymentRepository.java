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
}
