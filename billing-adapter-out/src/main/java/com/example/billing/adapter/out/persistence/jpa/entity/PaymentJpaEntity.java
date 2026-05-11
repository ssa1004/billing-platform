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
 * Payment persistence row — soft delete 적용 (ADR-0030). PG 트랜잭션 ID 가 박힌 row 라
 * 물리 삭제는 절대 금지. PG 측엔 살아있는데 우리 DB 에서 사라진 row 가 정합 깨짐 사고의
 * 가장 흔한 원인.
 */
@Entity
@Table(name = "payments")
@SQLRestriction("deleted_at IS NULL")
@SQLDelete(sql = "UPDATE payments SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND version = ?")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
public class PaymentJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "method", nullable = false, length = 32)
    private String method;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "pg_transaction_id", length = 256)
    private String pgTransactionId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 2048)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
