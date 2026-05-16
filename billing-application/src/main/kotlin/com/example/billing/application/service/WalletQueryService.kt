package com.example.billing.application.service

import com.example.billing.application.exception.WalletNotFoundException
import com.example.billing.application.port.`in`.WalletQueryUseCase
import com.example.billing.application.port.out.LedgerRepository
import com.example.billing.application.port.out.WalletRepository
import com.example.billing.domain.ledger.LedgerEntry
import com.example.billing.domain.wallet.Wallet
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class WalletQueryService(
    private val wallets: WalletRepository,
    private val ledger: LedgerRepository,
) : WalletQueryUseCase {

    @Cacheable(cacheNames = ["wallets"], key = "#ownerId")
    @Transactional(readOnly = true)
    override fun getByOwner(ownerId: String): Wallet =
        wallets.findByOwnerId(ownerId)
            .orElseThrow { WalletNotFoundException(ownerId) }

    @Transactional(readOnly = true)
    override fun recentTransactions(ownerId: String, limit: Int): List<LedgerEntry> {
        val wallet = getByOwner(ownerId)
        return ledger.findByWallet(wallet.id, limit)
    }
}
