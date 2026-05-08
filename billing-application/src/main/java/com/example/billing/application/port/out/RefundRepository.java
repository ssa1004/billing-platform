package com.example.billing.application.port.out;

import com.example.billing.domain.refund.Refund;
import com.example.billing.domain.refund.RefundId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RefundRepository {
    void save(Refund refund);
    Optional<Refund> findById(RefundId id);

    /**
     * Reconciler 가 호출. {@code requestedAt <= staleBefore} 인 REQUESTED Refund 들.
     * 3-phase 흐름의 phase 3 (DB tx2) 가 깨졌을 가능성이 있는 후보. limit 만큼만.
     */
    List<Refund> findStaleRequested(Instant staleBefore, int limit);
}
