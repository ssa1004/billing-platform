package com.example.wallet.application.port.out;

import com.example.wallet.domain.payment.Payment;
import com.example.wallet.domain.payment.PaymentId;

import java.util.Optional;

public interface PaymentRepository {
    void save(Payment payment);
    Optional<Payment> findById(PaymentId id);
    Optional<Payment> findByIdempotencyKey(String key);
}
