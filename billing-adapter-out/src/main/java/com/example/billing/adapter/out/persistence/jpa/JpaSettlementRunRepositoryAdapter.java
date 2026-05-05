package com.example.billing.adapter.out.persistence.jpa;

import com.example.billing.adapter.out.persistence.jpa.entity.SettlementRunJpaEntity;
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataSettlementRunRepository;
import com.example.billing.application.port.out.SettlementRunRepository;
import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.settlement.SettlementRun;
import com.example.billing.domain.settlement.SettlementStatus;
import com.example.billing.domain.shared.CustomerId;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaSettlementRunRepositoryAdapter implements SettlementRunRepository {

    private final SpringDataSettlementRunRepository jpa;

    public JpaSettlementRunRepositoryAdapter(SpringDataSettlementRunRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(SettlementRun run) {
        SettlementRunJpaEntity entity = jpa.findById(run.id()).orElseGet(SettlementRunJpaEntity::new);
        if (entity.getId() == null) entity.setId(run.id());
        entity.setPeriodYearMonth(run.period().toKey());
        entity.setCustomerId(run.customerId().map(CustomerId::value).orElse(null));
        entity.setStatus(run.status());
        entity.setStartedAt(run.startedAt());
        entity.setFinishedAt(run.finishedAt());
        entity.setInvoicesGenerated(run.invoicesGenerated());
        entity.setPaymentsAttempted(run.paymentsAttempted());
        entity.setPaymentsSucceeded(run.paymentsSucceeded());
        entity.setFailureReason(run.failureReason());
        entity.setCreatedAt(run.createdAt());
        jpa.save(entity);
    }

    @Override
    public Optional<SettlementRun> findById(UUID id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public List<SettlementRun> findByPeriod(BillingPeriod period) {
        return jpa.findByPeriodYearMonth(period.toKey()).stream().map(this::toDomain).toList();
    }

    @Override
    public List<SettlementRun> claimPendingForUpdateSkipLocked(BillingPeriod period, int limit) {
        return jpa.claimPendingForUpdate(period.toKey(), SettlementStatus.PENDING,
                        PageRequest.of(0, limit))
                .stream().map(this::toDomain).toList();
    }

    private SettlementRun toDomain(SettlementRunJpaEntity e) {
        return SettlementRun.restore(
                e.getId(),
                BillingPeriod.of(YearMonth.parse(e.getPeriodYearMonth())),
                e.getCustomerId() != null ? CustomerId.of(e.getCustomerId()) : null,
                e.getStatus(), e.getStartedAt(), e.getFinishedAt(),
                e.getInvoicesGenerated(), e.getPaymentsAttempted(), e.getPaymentsSucceeded(),
                e.getFailureReason(), e.getCreatedAt(), e.getVersion());
    }
}
