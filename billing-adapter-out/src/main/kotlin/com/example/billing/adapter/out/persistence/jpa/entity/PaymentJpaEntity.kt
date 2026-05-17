package com.example.billing.adapter.out.persistence.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.SQLRestriction
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Payment persistence row — soft delete 적용 (ADR-0030). PG 트랜잭션 ID 가 박힌 row 라
 * 물리 삭제는 절대 금지. PG 측엔 살아있는데 우리 DB 에서 사라진 row 가 정합 깨짐 사고의
 * 가장 흔한 원인.
 */
@Entity
@Table(name = "payments")
@SQLRestriction("deleted_at IS NULL")
@SQLDelete(sql = "UPDATE payments SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND version = ?")
class PaymentJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null

    @Column(name = "order_id", nullable = false)
    var orderId: UUID? = null

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    var amount: BigDecimal = BigDecimal.ZERO

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String = ""

    @Column(name = "method", nullable = false, length = 32)
    var method: String = ""

    @Column(name = "status", nullable = false, length = 32)
    var status: String = ""

    @Column(name = "pg_transaction_id", length = 256)
    var pgTransactionId: String? = null

    @Column(name = "idempotency_key", nullable = false, length = 128)
    var idempotencyKey: String = ""

    @Column(name = "error_code", length = 64)
    var errorCode: String? = null

    @Column(name = "error_message", length = 2048)
    var errorMessage: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

    /** 논리 삭제 시각. NULL 이면 활성 row. */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    /** 누가 삭제했나 — user / operator id. */
    @Column(name = "deleted_by", length = 128)
    var deletedBy: String? = null
}
