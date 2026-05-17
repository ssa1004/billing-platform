package com.example.billing.adapter.out.persistence.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * JPA persistence entity for Wallet. 도메인 객체와 분리 — 헥사고날.
 * 도메인의 [com.example.billing.domain.wallet.Wallet] 은 JPA 어노테이션 0.
 * 매핑은 [com.example.billing.adapter.out.persistence.jpa.mapper.WalletJpaMapper] 에서.
 */
@Entity
@Table(name = "wallets")
class WalletJpaEntity() {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null

    @Column(name = "owner_id", nullable = false, length = 128)
    var ownerId: String = ""

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String = ""

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    var balance: BigDecimal = BigDecimal.ZERO

    @Column(name = "blocked", nullable = false, precision = 19, scale = 4)
    var blocked: BigDecimal = BigDecimal.ZERO

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
        ownerId: String,
        currency: String,
        balance: BigDecimal,
        blocked: BigDecimal,
        createdAt: Instant,
        updatedAt: Instant,
        version: Long,
    ) : this() {
        this.id = id
        this.ownerId = ownerId
        this.currency = currency
        this.balance = balance
        this.blocked = blocked
        this.createdAt = createdAt
        this.updatedAt = updatedAt
        this.version = version
    }
}
