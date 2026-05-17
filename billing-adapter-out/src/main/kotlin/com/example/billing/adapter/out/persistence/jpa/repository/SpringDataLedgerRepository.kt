package com.example.billing.adapter.out.persistence.jpa.repository

import com.example.billing.adapter.out.persistence.jpa.entity.LedgerEntryJpaEntity
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface SpringDataLedgerRepository : JpaRepository<LedgerEntryJpaEntity, Long> {

    @Query("SELECT l FROM LedgerEntryJpaEntity l WHERE l.walletId = :walletId ORDER BY l.occurredAt DESC")
    fun findByWalletId(walletId: UUID, pageRequest: PageRequest): List<LedgerEntryJpaEntity>
}
