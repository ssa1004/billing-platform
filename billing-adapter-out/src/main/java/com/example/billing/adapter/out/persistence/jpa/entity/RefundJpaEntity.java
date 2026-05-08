package com.example.billing.adapter.out.persistence.jpa.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Refund persistence row — soft delete 적용 (ADR-0030). PG 환불 ID 매칭이 살아있어야
 * 정합 검증 (reconciler) 이 동작하므로 물리 삭제 금지.
 */
@Entity
@Table(name = "refunds")
@SQLRestriction("deleted_at IS NULL")
@SQLDelete(sql = "UPDATE refunds SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND version = ?")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
public class RefundJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "pg_refund_id", length = 256)
    private String pgRefundId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** 논리 삭제 시각. NULL 이면 활성 row. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** 누가 삭제했나 — user / operator id. */
    @Column(name = "deleted_by", length = 128)
    private String deletedBy;
}
