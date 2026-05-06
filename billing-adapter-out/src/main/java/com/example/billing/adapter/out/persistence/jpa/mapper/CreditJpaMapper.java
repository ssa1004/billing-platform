package com.example.billing.adapter.out.persistence.jpa.mapper;

import com.example.billing.adapter.out.persistence.jpa.entity.CreditJpaEntity;
import com.example.billing.domain.credit.Credit;
import com.example.billing.domain.credit.CreditId;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;

import java.util.Currency;

/** Credit ↔ JpaEntity. 도메인은 JPA 의존성 0 → 매핑은 어댑터 책임. */
public final class CreditJpaMapper {

    private CreditJpaMapper() {}

    public static CreditJpaEntity toEntity(Credit c) {
        return new CreditJpaEntity(
                c.id().value(),
                c.customerId().value(),
                c.type(),
                c.currency().getCurrencyCode(),
                c.grantedAmount().amount(),
                c.balance().amount(),
                c.validFrom(),
                c.validUntil(),
                c.status(),
                c.reason(),
                c.createdAt(),
                c.updatedAt(),
                c.version()
        );
    }

    public static Credit toDomain(CreditJpaEntity e) {
        Currency currency = Currency.getInstance(e.getCurrency());
        return Credit.restore(
                new CreditId(e.getId()),
                CustomerId.of(e.getCustomerId()),
                e.getType(),
                currency,
                Money.of(e.getGrantedAmount(), currency),
                Money.of(e.getBalance(), currency),
                e.getValidFrom(),
                e.getValidUntil(),
                e.getStatus(),
                e.getReason(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getVersion()
        );
    }
}
