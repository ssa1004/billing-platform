package com.example.billing.application.port.out

import com.example.billing.domain.settlement.BillingPeriod
import com.example.billing.domain.settlement.SettlementRun
import java.util.Optional
import java.util.UUID

interface SettlementRunRepository {

    fun save(run: SettlementRun)

    fun findById(id: UUID): Optional<SettlementRun>

    fun findByPeriod(period: BillingPeriod): List<SettlementRun>

    /**
     * PENDING 상태의 SettlementRun 을 worker pool 이 SKIP LOCKED 로 가져감.
     * @return 잡힌 행 (lock 보유). 트랜잭션 안에서 처리해야 함.
     */
    fun claimPendingForUpdateSkipLocked(period: BillingPeriod, limit: Int): List<SettlementRun>
}
