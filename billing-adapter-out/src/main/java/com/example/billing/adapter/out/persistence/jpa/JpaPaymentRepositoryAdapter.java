package com.example.billing.adapter.out.persistence.jpa;

import com.example.billing.adapter.out.persistence.jpa.mapper.PaymentJpaMapper;
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataPaymentRepository;
import com.example.billing.application.port.out.PaymentRepository;
import com.example.billing.domain.payment.Payment;
import com.example.billing.domain.payment.PaymentId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
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

    @Override
    public List<Payment> findStalePending(Instant staleBefore, int limit) {
        return jpa.findStalePending(staleBefore, PageRequest.of(0, limit)).stream()
                .map(PaymentJpaMapper::toDomain)
                .toList();
    }
}
