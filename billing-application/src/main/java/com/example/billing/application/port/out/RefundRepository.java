package com.example.billing.application.port.out;

import com.example.billing.domain.refund.Refund;
import com.example.billing.domain.refund.RefundId;

import java.util.Optional;

public interface RefundRepository {
    void save(Refund refund);
    Optional<Refund> findById(RefundId id);
}
