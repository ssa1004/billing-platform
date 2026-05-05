package com.example.wallet.adapter.out.persistence.jpa;

import com.example.wallet.adapter.out.persistence.jpa.mapper.RefundJpaMapper;
import com.example.wallet.adapter.out.persistence.jpa.repository.SpringDataRefundRepository;
import com.example.wallet.application.port.out.RefundRepository;
import com.example.wallet.domain.refund.Refund;
import com.example.wallet.domain.refund.RefundId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

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
}
