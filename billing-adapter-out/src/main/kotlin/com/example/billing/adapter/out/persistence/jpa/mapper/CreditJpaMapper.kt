package com.example.billing.adapter.out.persistence.jpa.mapper

import com.example.billing.adapter.out.persistence.jpa.entity.CreditJpaEntity
import com.example.billing.domain.credit.Credit
import com.example.billing.domain.credit.CreditId
import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.shared.Money
import java.util.Currency

/** Credit ↔ JpaEntity. 도메인은 JPA 의존성 0 → 매핑은 어댑터 책임. */
object CreditJpaMapper {

    @JvmStatic
    fun toEntity(c: Credit): CreditJpaEntity = CreditJpaEntity(
        c.id.value,
        c.customerId.value,
        c.type,
        c.currency.currencyCode,
        c.grantedAmount.amount,
        c.balance.amount,
        c.validFrom,
        c.validUntil,
        c.status,
        c.reason,
        c.createdAt,
        c.updatedAt,
        c.version,
    )

    @JvmStatic
    fun toDomain(e: CreditJpaEntity): Credit {
        val currency = Currency.getInstance(e.currency)
        return Credit.restore(
            CreditId(e.id!!),
            CustomerId.of(e.customerId),
            e.type,
            currency,
            Money.of(e.grantedAmount, currency),
            Money.of(e.balance, currency),
            e.validFrom,
            e.validUntil,
            e.status,
            e.reason,
            e.createdAt,
            e.updatedAt,
            e.version,
        )
    }
}
