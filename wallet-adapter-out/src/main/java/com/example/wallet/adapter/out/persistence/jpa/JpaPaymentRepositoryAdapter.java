package com.example.wallet.adapter.out.persistence.jpa;

import com.example.wallet.adapter.out.persistence.jpa.mapper.PaymentJpaMapper;
import com.example.wallet.adapter.out.persistence.jpa.repository.SpringDataPaymentRepository;
import com.example.wallet.application.port.out.PaymentRepository;
import com.example.wallet.domain.payment.Payment;
import com.example.wallet.domain.payment.PaymentId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaPaymentRepositoryAdapter implements PaymentRepository {

    private final SpringDataPaymentRepository jpa;

    @Override
    public void save(Payment payment) {
        jpa.save(PaymentJpaMapper.toEntity(payment));
    }

    @Override
    public Optional<Payment> findById(PaymentId id) {
        return jpa.findById(id.value()).map(PaymentJpaMapper::toDomain);
    }

    @Override
    public Optional<Payment> findByIdempotencyKey(String key) {
        return jpa.findByIdempotencyKey(key).map(PaymentJpaMapper::toDomain);
    }
}
