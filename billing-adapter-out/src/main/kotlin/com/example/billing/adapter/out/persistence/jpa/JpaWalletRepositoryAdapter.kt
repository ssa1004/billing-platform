package com.example.billing.adapter.out.persistence.jpa

import com.example.billing.adapter.out.persistence.jpa.mapper.WalletJpaMapper
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataWalletRepository
import com.example.billing.application.port.out.WalletRepository
import com.example.billing.domain.wallet.Wallet
import com.example.billing.domain.wallet.WalletId
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
class JpaWalletRepositoryAdapter(
    private val jpa: SpringDataWalletRepository,
) : WalletRepository {

    override fun save(wallet: Wallet) {
        // domain → entity. Wallet 의 version 필드를 그대로 보냄 → JPA 가 낙관적 락 검증
        val entity = WalletJpaMapper.toEntity(wallet)
        jpa.save(entity)
    }

    override fun findById(id: WalletId): Optional<Wallet> =
        jpa.findById(id.value).map(WalletJpaMapper::toDomain)

    override fun findByOwnerId(ownerId: String): Optional<Wallet> =
        jpa.findByOwnerId(ownerId).map(WalletJpaMapper::toDomain)
}
