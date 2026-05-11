package com.example.billing.adapter.out.persistence.jpa.entity;

import com.example.billing.domain.invoice.InvoiceStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Invoice persistence row — soft delete 적용 (ADR-0030).
 *
 * <p><b>{@link SQLRestriction}</b>: 모든 read query 에 자동으로 {@code deleted_at IS NULL} 을
 * AND 로 끼워줍니다. 활성 row 만 보이는 게 기본. 삭제된 row 까지 봐야 하는 운영자용 화면은
 * 이 엔티티를 거치지 않고 NativeQuery 로 풀어야 합니다 (의도적으로 어렵게).</p>
 *
 * <p><b>{@link SQLDelete}</b>: {@code repository.delete(entity)} 호출 시 실제로는 UPDATE 실행 —
 * deleted_at 만 NOW() 로 채우고 row 자체는 남깁니다. 누가 (deleted_by) 지웠는지는 도메인 서비스가
 * SoftDeleteService 를 통해 명시적으로 채운 뒤 호출. {@code @SQLDelete} 만으로는 deleted_by 를
 * 알 수 없어서 deleted_at 만 채우고, deleted_by 는 호출자가 사전에 setter 로 셋업.</p>
 */
@Entity
@Table(name = "invoices", uniqueConstraints = {
        @UniqueConstraint(name = "uq_invoice_customer_period",
                columnNames = {"customer_id", "period_year_month"})
}, indexes = {
        @Index(name = "idx_invoice_status_due", columnList = "status, due_at"),
        @Index(name = "idx_invoice_customer", columnList = "customer_id")
})
@SQLRestriction("deleted_at IS NULL")
@SQLDelete(sql = "UPDATE invoices SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND version = ?")
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

    /** Credit 으로 차감된 금액 누적. {@code amountDue = totalAmount - appliedCredit}. */
    @Column(name = "applied_credit", nullable = false, precision = 19, scale = 2)
    private BigDecimal appliedCredit;

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

    /** 논리 삭제 시각. NULL 이면 활성 row. ADR-0030 참조. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** 누가 삭제했나 — user / operator id. deleted_at 과 항상 짝. */
    @Column(name = "deleted_by", length = 128)
    private String deletedBy;
}
