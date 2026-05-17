package com.example.billing.adapter.out.persistence.jpa.mapper

import com.example.billing.adapter.out.persistence.jpa.entity.LedgerEntryJpaEntity
import com.example.billing.adapter.out.persistence.jpa.entity.WalletJpaEntity
import com.example.billing.domain.ledger.LedgerEntry
import com.example.billing.domain.shared.Money
import com.example.billing.domain.shared.Reference
import com.example.billing.domain.wallet.WalletId
import java.util.Currency

object LedgerJpaMapper {

    @JvmStatic
    fun toEntity(l: LedgerEntry, wallet: WalletJpaEntity): LedgerEntryJpaEntity {
        val e = LedgerEntryJpaEntity()
        e.walletId = l.walletId.value
        e.entryType = l.entryType.name
        e.amount = l.amount.amount
        e.balanceAfter = l.balanceAfter.amount
        l.reference?.let {
            e.referenceType = it.type.name
            e.referenceId = it.id
        }
        e.traceId = l.traceId
        e.occurredAt = l.occurredAt
        return e
    }

    @JvmStatic
    fun toDomain(e: LedgerEntryJpaEntity, currency: Currency): LedgerEntry {
        val refType = e.referenceType
        val ref = if (refType != null) {
            Reference(Reference.Type.valueOf(refType), e.referenceId!!)
        } else {
            null
        }
        return LedgerEntry(
            WalletId(e.walletId!!),
            LedgerEntry.EntryType.valueOf(e.entryType),
            Money.of(e.amount, currency),
            Money.of(e.balanceAfter, currency),
            ref,
            e.traceId,
            e.occurredAt,
        )
    }
}
