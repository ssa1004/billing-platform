package com.example.billing.adapter.out.persistence.jpa.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "budget_alert_history")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
public class BudgetAlertHistoryJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "threshold_amount_at_trigger", nullable = false, precision = 18, scale = 2)
    private BigDecimal thresholdAmountAtTrigger;

    @Column(name = "projected_cost_at_trigger", nullable = false, precision = 18, scale = 2)
    private BigDecimal projectedCostAtTrigger;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "overshoot_ratio", nullable = false)
    private double overshootRatio;

    @Column(name = "period_year_month", nullable = false, length = 7)
    private String periodYearMonth;

    @Column(name = "period_progress_ratio_at_trigger", nullable = false)
    private double periodProgressRatioAtTrigger;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
