package com.example.billing.adapter.out.persistence.jpa.entity

import com.example.billing.domain.settlement.SettlementStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "settlement_runs",
    indexes = [
        Index(name = "idx_settlement_period_status", columnList = "period_year_month, status"),
        Index(name = "idx_settlement_customer_period", columnList = "customer_id, period_year_month"),
    ],
)
class SettlementRunJpaEntity {

    @Id
    var id: UUID? = null

    @Column(name = "period_year_month", nullable = false, length = 7)
    var periodYearMonth: String = ""

    @Column(name = "customer_id", length = 64)
    var customerId: String? = null  // null = aggregate row (전체 통계)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: SettlementStatus = SettlementStatus.PENDING

    @Column(name = "started_at")
    var startedAt: Instant? = null

    @Column(name = "finished_at")
    var finishedAt: Instant? = null

    @Column(name = "invoices_generated", nullable = false)
    var invoicesGenerated: Int = 0

    @Column(name = "payments_attempted", nullable = false)
    var paymentsAttempted: Int = 0

    @Column(name = "payments_succeeded", nullable = false)
    var paymentsSucceeded: Int = 0

    @Column(name = "failure_reason", length = 1024)
    var failureReason: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH

    @Version
    @Column(nullable = false)
    var version: Long = 0
}
