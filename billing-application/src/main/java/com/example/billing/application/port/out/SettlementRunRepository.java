package com.example.billing.application.port.out;

import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.settlement.SettlementRun;
import com.example.billing.domain.settlement.SettlementStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementRunRepository {

    void save(SettlementRun run);

    Optional<SettlementRun> findById(UUID id);

    List<SettlementRun> findByPeriod(BillingPeriod period);

    /**
     * PENDING 상태의 SettlementRun 을 worker pool 이 SKIP LOCKED 로 가져감.
     * @return 잡힌 행 (lock 보유). 트랜잭션 안에서 처리해야 함.
     */
    List<SettlementRun> claimPendingForUpdateSkipLocked(BillingPeriod period, int limit);
}
