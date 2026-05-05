package com.example.wallet.application.port.out;

import com.example.wallet.domain.refund.Refund;
import com.example.wallet.domain.refund.RefundId;

import java.util.Optional;

public interface RefundRepository {
    void save(Refund refund);
    Optional<Refund> findById(RefundId id);
}
