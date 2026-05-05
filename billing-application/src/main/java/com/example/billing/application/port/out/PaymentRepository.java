package com.example.billing.application.port.out;

import com.example.billing.domain.payment.Payment;
import com.example.billing.domain.payment.PaymentId;

import java.util.Optional;

public interface PaymentRepository {
    void save(Payment payment);
    Optional<Payment> findById(PaymentId id);
    Optional<Payment> findByIdempotencyKey(String key);
}
