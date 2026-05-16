package com.example.billing.application.port.out

import com.example.billing.domain.ledger.LedgerEntry
import com.example.billing.domain.wallet.WalletId

interface LedgerRepository {
    fun append(entry: LedgerEntry)
    fun findByWallet(walletId: WalletId, limit: Int): List<LedgerEntry>
}
