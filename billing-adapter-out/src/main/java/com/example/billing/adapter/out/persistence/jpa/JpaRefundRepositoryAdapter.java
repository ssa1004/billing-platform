package com.example.billing.adapter.out.persistence.jpa;

import com.example.billing.adapter.out.persistence.jpa.mapper.RefundJpaMapper;
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataRefundRepository;
import com.example.billing.application.port.out.RefundRepository;
import com.example.billing.domain.refund.Refund;
import com.example.billing.domain.refund.RefundId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaRefundRepositoryAdapter implements RefundRepository {

    private final SpringDataRefundRepository jpa;

    @Override
    public void save(Refund refund) {
        jpa.save(RefundJpaMapper.toEntity(refund));
    }

    @Override
    public Optional<Refund> findById(RefundId id) {
        return jpa.findById(id.value()).map(RefundJpaMapper::toDomain);
    }

    @Override
    public List<Refund> findStaleRequested(Instant staleBefore, int limit) {
        return jpa.findStaleRequested(staleBefore, PageRequest.of(0, limit)).stream()
                .map(RefundJpaMapper::toDomain)
                .toList();
    }
}
