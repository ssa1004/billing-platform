package com.example.billing.application.port.out

import com.example.billing.domain.wallet.Wallet
import com.example.billing.domain.wallet.WalletId
import java.util.Optional

interface WalletRepository {

    fun save(wallet: Wallet)

    fun findById(id: WalletId): Optional<Wallet>

    fun findByOwnerId(ownerId: String): Optional<Wallet>
}
