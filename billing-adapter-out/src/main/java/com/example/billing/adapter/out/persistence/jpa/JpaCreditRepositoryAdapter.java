package com.example.billing.adapter.out.persistence.jpa;

import com.example.billing.adapter.out.persistence.jpa.mapper.CreditJpaMapper;
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataCreditRepository;
import com.example.billing.application.port.out.CreditRepository;
import com.example.billing.domain.credit.Credit;
import com.example.billing.domain.credit.CreditId;
import com.example.billing.domain.shared.CustomerId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaCreditRepositoryAdapter implements CreditRepository {

    private final SpringDataCreditRepository jpa;

    @Override
    public void save(Credit credit) {
        jpa.save(CreditJpaMapper.toEntity(credit));
    }

    @Override
    public Optional<Credit> findById(CreditId id) {
        return jpa.findById(id.value()).map(CreditJpaMapper::toDomain);
    }

    @Override
    public List<Credit> findUsable(CustomerId customerId, Instant now) {
        return jpa.findUsable(customerId.value(), now).stream()
                .map(CreditJpaMapper::toDomain)
                .toList();
    }

    @Override
    public List<Credit> findExpiredCandidates(Instant now, int limit) {
        return jpa.findExpiredCandidates(now, org.springframework.data.domain.PageRequest.of(0, limit))
                .stream()
                .map(CreditJpaMapper::toDomain)
                .toList();
    }
}
