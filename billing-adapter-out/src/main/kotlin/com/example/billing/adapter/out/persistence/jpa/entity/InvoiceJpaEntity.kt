package com.example.billing.adapter.out.persistence.jpa.entity

import com.example.billing.domain.invoice.InvoiceStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.SQLRestriction
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Invoice persistence row — soft delete 적용 (ADR-0030).
 *
 * [SQLRestriction]: 모든 read query 에 자동으로 `deleted_at IS NULL` 을
 * AND 로 끼워줍니다. 활성 row 만 보이는 게 기본. 삭제된 row 까지 봐야 하는 운영자용 화면은
 * 이 엔티티를 거치지 않고 NativeQuery 로 풀어야 합니다 (의도적으로 어렵게).
 *
 * [SQLDelete]: `repository.delete(entity)` 호출 시 실제로는 UPDATE 실행 —
 * deleted_at 만 NOW() 로 채우고 row 자체는 남깁니다. 누가 (deleted_by) 지웠는지는 도메인 서비스가
 * SoftDeleteService 를 통해 명시적으로 채운 뒤 호출. `@SQLDelete` 만으로는 deleted_by 를
 * 알 수 없어서 deleted_at 만 채우고, deleted_by 는 호출자가 사전에 setter 로 셋업.
 */
@Entity
@Table(
    name = "invoices",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_invoice_customer_period",
            columnNames = ["customer_id", "period_year_month"],
        ),
    ],
    indexes = [
        Index(name = "idx_invoice_status_due", columnList = "status, due_at"),
        Index(name = "idx_invoice_customer", columnList = "customer_id"),
    ],
)
@SQLRestriction("deleted_at IS NULL")
@SQLDelete(sql = "UPDATE invoices SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND version = ?")
class InvoiceJpaEntity {

    @Id
    var id: UUID? = null

    @Column(name = "customer_id", nullable = false, length = 64)
    var customerId: String = ""

    @Column(name = "period_year_month", nullable = false, length = 7)
    var periodYearMonth: String = ""

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    var totalAmount: BigDecimal = BigDecimal.ZERO

    /** Credit 으로 차감된 금액 누적. `amountDue = totalAmount - appliedCredit`. */
    @Column(name = "applied_credit", nullable = false, precision = 19, scale = 2)
    var appliedCredit: BigDecimal = BigDecimal.ZERO

    @Column(name = "currency_code", nullable = false, length = 3)
    var currencyCode: String = ""

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: InvoiceStatus = InvoiceStatus.DRAFT

    /** InvoiceLine 리스트 + PricingSnapshot 의 JSON 직렬화. */
    @Column(name = "lines_json", nullable = false, columnDefinition = "TEXT")
    var linesJson: String = ""

    @Column(name = "pricing_snapshot_json", nullable = false, columnDefinition = "TEXT")
    var pricingSnapshotJson: String = ""

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH

    @Column(name = "issued_at")
    var issuedAt: Instant? = null

    @Column(name = "due_at")
    var dueAt: Instant? = null

    @Column(name = "paid_at")
    var paidAt: Instant? = null

    @Version
    @Column(nullable = false)
    var version: Long = 0

    /** 논리 삭제 시각. NULL 이면 활성 row. ADR-0030 참조. */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    /** 누가 삭제했나 — user / operator id. deleted_at 과 항상 짝. */
    @Column(name = "deleted_by", length = 128)
    var deletedBy: String? = null
}
