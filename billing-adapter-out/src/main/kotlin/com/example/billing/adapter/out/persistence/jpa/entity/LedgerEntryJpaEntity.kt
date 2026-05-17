package com.example.billing.adapter.out.persistence.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "ledger_entries")
class LedgerEntryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null

    @Column(name = "wallet_id", nullable = false)
    var walletId: UUID? = null

    @Column(name = "entry_type", nullable = false, length = 32)
    var entryType: String = ""

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    var amount: BigDecimal = BigDecimal.ZERO

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    var balanceAfter: BigDecimal = BigDecimal.ZERO

    @Column(name = "reference_type", length = 32)
    var referenceType: String? = null

    @Column(name = "reference_id", length = 128)
    var referenceId: String? = null

    @Column(name = "trace_id", length = 64)
    var traceId: String? = null

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant = Instant.EPOCH
}
