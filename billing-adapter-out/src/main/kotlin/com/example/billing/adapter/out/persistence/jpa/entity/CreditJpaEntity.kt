package com.example.billing.adapter.out.persistence.jpa.entity

import com.example.billing.domain.credit.CreditStatus
import com.example.billing.domain.credit.CreditType
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

/**
 * JPA persistence entity for Credit. 도메인 [com.example.billing.domain.credit.Credit]
 * 와 분리. 매핑은 [com.example.billing.adapter.out.persistence.jpa.mapper.CreditJpaMapper].
 */
@Entity
@Table(name = "credits")
class CreditJpaEntity() {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null

    @Column(name = "customer_id", nullable = false, length = 64)
    var customerId: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    var type: CreditType = CreditType.PROMO

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String = ""

    @Column(name = "granted_amount", nullable = false, precision = 18, scale = 2)
    var grantedAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "balance", nullable = false, precision = 18, scale = 2)
    var balance: BigDecimal = BigDecimal.ZERO

    @Column(name = "valid_from", nullable = false)
    var validFrom: Instant = Instant.EPOCH

    @Column(name = "valid_until")
    var validUntil: Instant? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: CreditStatus = CreditStatus.ACTIVE

    @Column(name = "reason", length = 256)
    var reason: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

    constructor(
        id: UUID?,
        customerId: String,
        type: CreditType,
        currency: String,
        grantedAmount: BigDecimal,
        balance: BigDecimal,
        validFrom: Instant,
        validUntil: Instant?,
        status: CreditStatus,
        reason: String?,
        createdAt: Instant,
        updatedAt: Instant,
        version: Long,
    ) : this() {
        this.id = id
        this.customerId = customerId
        this.type = type
        this.currency = currency
        this.grantedAmount = grantedAmount
        this.balance = balance
        this.validFrom = validFrom
        this.validUntil = validUntil
        this.status = status
        this.reason = reason
        this.createdAt = createdAt
        this.updatedAt = updatedAt
        this.version = version
    }
}
