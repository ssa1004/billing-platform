package com.example.billing.adapter.out.persistence.jpa.mapper

import com.example.billing.adapter.out.persistence.jpa.entity.WalletJpaEntity
import com.example.billing.domain.shared.Money
import com.example.billing.domain.wallet.Wallet
import com.example.billing.domain.wallet.WalletId
import java.util.Currency

/** JPA entity ↔ Wallet domain. 도메인은 JPA 의존성 0 → 매핑은 어댑터 책임. */
object WalletJpaMapper {

    @JvmStatic
    fun toEntity(w: Wallet): WalletJpaEntity = WalletJpaEntity(
        w.id.value,
        w.ownerId,
        w.currency.currencyCode,
        w.balance.amount,
        w.blocked.amount,
        w.createdAt,
        w.updatedAt,
        w.version,
    )

    @JvmStatic
    fun toDomain(e: WalletJpaEntity): Wallet {
        val currency = Currency.getInstance(e.currency)
        return Wallet.restore(
            WalletId(e.id!!),
            e.ownerId,
            currency,
            Money.of(e.balance, currency),
            Money.of(e.blocked, currency),
            e.createdAt,
            e.updatedAt,
            e.version,
        )
    }
}
