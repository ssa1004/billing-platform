package com.example.billing.adapter.out.persistence.jpa.repository

import com.example.billing.adapter.out.persistence.jpa.entity.WalletJpaEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface SpringDataWalletRepository : JpaRepository<WalletJpaEntity, UUID> {
    fun findByOwnerId(ownerId: String): Optional<WalletJpaEntity>
}
