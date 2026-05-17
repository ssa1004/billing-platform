package com.example.billing.adapter.out.persistence.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "budget_alert_history")
class BudgetAlertHistoryJpaEntity() {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null

    @Column(name = "rule_id", nullable = false)
    var ruleId: UUID? = null

    @Column(name = "customer_id", nullable = false, length = 64)
    var customerId: String = ""

    @Column(name = "threshold_amount_at_trigger", nullable = false, precision = 18, scale = 2)
    var thresholdAmountAtTrigger: BigDecimal = BigDecimal.ZERO

    @Column(name = "projected_cost_at_trigger", nullable = false, precision = 18, scale = 2)
    var projectedCostAtTrigger: BigDecimal = BigDecimal.ZERO

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String = ""

    @Column(name = "overshoot_ratio", nullable = false)
    var overshootRatio: Double = 0.0

    @Column(name = "period_year_month", nullable = false, length = 7)
    var periodYearMonth: String = ""

    @Column(name = "period_progress_ratio_at_trigger", nullable = false)
    var periodProgressRatioAtTrigger: Double = 0.0

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant = Instant.EPOCH

    /** Lombok `@AllArgsConstructor` 호환 — mapper 가 positional 로 생성. */
    constructor(
        id: UUID?,
        ruleId: UUID?,
        customerId: String,
        thresholdAmountAtTrigger: BigDecimal,
        projectedCostAtTrigger: BigDecimal,
        currency: String,
        overshootRatio: Double,
        periodYearMonth: String,
        periodProgressRatioAtTrigger: Double,
        occurredAt: Instant,
    ) : this() {
        this.id = id
        this.ruleId = ruleId
        this.customerId = customerId
        this.thresholdAmountAtTrigger = thresholdAmountAtTrigger
        this.projectedCostAtTrigger = projectedCostAtTrigger
        this.currency = currency
        this.overshootRatio = overshootRatio
        this.periodYearMonth = periodYearMonth
        this.periodProgressRatioAtTrigger = periodProgressRatioAtTrigger
        this.occurredAt = occurredAt
    }
}
