package com.example.billing.application.port.`in`

import com.example.billing.domain.ledger.LedgerEntry
import com.example.billing.domain.wallet.Wallet

interface WalletQueryUseCase {
    fun getByOwner(ownerId: String): Wallet
    fun recentTransactions(ownerId: String, limit: Int): List<LedgerEntry>
}
