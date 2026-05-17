package com.example.billing.adapter.out.persistence.jpa.entity

import com.example.billing.domain.budget.BudgetAlertStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "budget_alert_rules")
class BudgetAlertRuleJpaEntity() {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null

    @Column(name = "customer_id", nullable = false, length = 64)
    var customerId: String = ""

    @Column(name = "threshold_amount", nullable = false, precision = 18, scale = 2)
    var thresholdAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String = ""

    @Column(name = "cooldown_seconds", nullable = false)
    var cooldownSeconds: Long = 0

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: BudgetAlertStatus = BudgetAlertStatus.ACTIVE

    @Column(name = "last_evaluated_at")
    var lastEvaluatedAt: Instant? = null

    @Column(name = "last_triggered_at")
    var lastTriggeredAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

    /** Lombok `@AllArgsConstructor` 호환 — mapper 가 positional 로 생성. */
    constructor(
        id: UUID?,
        customerId: String,
        thresholdAmount: BigDecimal,
        currency: String,
        cooldownSeconds: Long,
        status: BudgetAlertStatus,
        lastEvaluatedAt: Instant?,
        lastTriggeredAt: Instant?,
        createdAt: Instant,
        updatedAt: Instant,
        version: Long,
    ) : this() {
        this.id = id
        this.customerId = customerId
        this.thresholdAmount = thresholdAmount
        this.currency = currency
        this.cooldownSeconds = cooldownSeconds
        this.status = status
        this.lastEvaluatedAt = lastEvaluatedAt
        this.lastTriggeredAt = lastTriggeredAt
        this.createdAt = createdAt
        this.updatedAt = updatedAt
        this.version = version
    }
}
