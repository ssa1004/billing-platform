package com.example.billing.adapter.out.persistence.jpa.entity;

import com.example.billing.domain.settlement.SettlementStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "settlement_runs", indexes = {
        @Index(name = "idx_settlement_period_status",
                columnList = "period_year_month, status"),
        @Index(name = "idx_settlement_customer_period",
                columnList = "customer_id, period_year_month")
})
@Getter
@Setter
@NoArgsConstructor
public class SettlementRunJpaEntity {

    @Id
    private UUID id;

    @Column(name = "period_year_month", nullable = false, length = 7)
    private String periodYearMonth;

    @Column(name = "customer_id", length = 64)
    private String customerId;  // null = aggregate row (전체 통계)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementStatus status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "invoices_generated", nullable = false)
    private int invoicesGenerated;

    @Column(name = "payments_attempted", nullable = false)
    private int paymentsAttempted;

    @Column(name = "payments_succeeded", nullable = false)
    private int paymentsSucceeded;

    @Column(name = "failure_reason", length = 1024)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private long version;
}
