package com.example.billing.adapter.out.persistence.jpa.entity;

import com.example.billing.domain.invoice.InvoiceStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invoices", uniqueConstraints = {
        @UniqueConstraint(name = "uq_invoice_customer_period",
                columnNames = {"customer_id", "period_year_month"})
}, indexes = {
        @Index(name = "idx_invoice_status_due", columnList = "status, due_at"),
        @Index(name = "idx_invoice_customer", columnList = "customer_id")
})
@Getter
@Setter
@NoArgsConstructor
public class InvoiceJpaEntity {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "period_year_month", nullable = false, length = 7)
    private String periodYearMonth;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvoiceStatus status;

    /** InvoiceLine 리스트 + PricingSnapshot 의 JSON 직렬화. */
    @Column(name = "lines_json", nullable = false, columnDefinition = "TEXT")
    private String linesJson;

    @Column(name = "pricing_snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String pricingSnapshotJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Version
    @Column(nullable = false)
    private long version;
}
