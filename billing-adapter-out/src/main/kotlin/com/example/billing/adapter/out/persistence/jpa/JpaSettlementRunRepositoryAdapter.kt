package com.example.billing.adapter.out.persistence.jpa

import com.example.billing.adapter.out.persistence.jpa.entity.SettlementRunJpaEntity
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataSettlementRunRepository
import com.example.billing.application.port.out.SettlementRunRepository
import com.example.billing.domain.settlement.BillingPeriod
import com.example.billing.domain.settlement.SettlementRun
import com.example.billing.domain.settlement.SettlementStatus
import com.example.billing.domain.shared.CustomerId
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.YearMonth
import java.util.Optional
import java.util.UUID

@Repository
class JpaSettlementRunRepositoryAdapter(
    private val jpa: SpringDataSettlementRunRepository,
) : SettlementRunRepository {

    override fun save(run: SettlementRun) {
        val entity = jpa.findById(run.id).orElseGet { SettlementRunJpaEntity() }
        if (entity.id == null) entity.id = run.id
        entity.periodYearMonth = run.period.toKey()
        entity.customerId = run.customerId().map(CustomerId::value).orElse(null)
        entity.status = run.status
        entity.startedAt = run.startedAt
        entity.finishedAt = run.finishedAt
        entity.invoicesGenerated = run.invoicesGenerated
        entity.paymentsAttempted = run.paymentsAttempted
        entity.paymentsSucceeded = run.paymentsSucceeded
        entity.failureReason = run.failureReason
        entity.createdAt = run.createdAt
        jpa.save(entity)
    }

    override fun findById(id: UUID): Optional<SettlementRun> =
        jpa.findById(id).map(::toDomain)

    override fun findByPeriod(period: BillingPeriod): List<SettlementRun> =
        jpa.findByPeriodYearMonth(period.toKey()).map(::toDomain)

    override fun claimPendingForUpdateSkipLocked(period: BillingPeriod, limit: Int): List<SettlementRun> =
        jpa.claimPendingForUpdate(period.toKey(), SettlementStatus.PENDING, PageRequest.of(0, limit))
            .map(::toDomain)

    private fun toDomain(e: SettlementRunJpaEntity): SettlementRun = SettlementRun.restore(
        e.id!!,
        BillingPeriod.of(YearMonth.parse(e.periodYearMonth)),
        e.customerId?.let(CustomerId::of),
        e.status,
        e.startedAt,
        e.finishedAt,
        e.invoicesGenerated,
        e.paymentsAttempted,
        e.paymentsSucceeded,
        e.failureReason,
        e.createdAt,
        e.version,
    )
}
