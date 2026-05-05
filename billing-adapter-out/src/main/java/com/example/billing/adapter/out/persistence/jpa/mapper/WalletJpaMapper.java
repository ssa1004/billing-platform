package com.example.billing.adapter.out.persistence.jpa.mapper;

import com.example.billing.adapter.out.persistence.jpa.entity.WalletJpaEntity;
import com.example.billing.domain.shared.Money;
import com.example.billing.domain.wallet.Wallet;
import com.example.billing.domain.wallet.WalletId;

import java.util.Currency;

/** JPA entity ↔ Wallet domain. 도메인은 JPA 의존성 0 → 매핑은 어댑터 책임. */
public final class WalletJpaMapper {

    private WalletJpaMapper() {}

    public static WalletJpaEntity toEntity(Wallet w) {
        return new WalletJpaEntity(
                w.id().value(),
                w.ownerId(),
                w.currency().getCurrencyCode(),
                w.balance().amount(),
                w.blocked().amount(),
                w.createdAt(),
                w.updatedAt(),
                w.version()
        );
    }

    public static Wallet toDomain(WalletJpaEntity e) {
        Currency currency = Currency.getInstance(e.getCurrency());
        return Wallet.restore(
                new WalletId(e.getId()),
                e.getOwnerId(),
                currency,
                Money.of(e.getBalance(), currency),
                Money.of(e.getBlocked(), currency),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getVersion()
        );
    }
}
