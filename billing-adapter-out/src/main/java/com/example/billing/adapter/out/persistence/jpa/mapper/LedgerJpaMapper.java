package com.example.billing.adapter.out.persistence.jpa.mapper;

import com.example.billing.adapter.out.persistence.jpa.entity.LedgerEntryJpaEntity;
import com.example.billing.adapter.out.persistence.jpa.entity.WalletJpaEntity;
import com.example.billing.domain.ledger.LedgerEntry;
import com.example.billing.domain.shared.Money;
import com.example.billing.domain.shared.Reference;
import com.example.billing.domain.wallet.WalletId;

import java.util.Currency;

public final class LedgerJpaMapper {

    private LedgerJpaMapper() {}

    public static LedgerEntryJpaEntity toEntity(LedgerEntry l, WalletJpaEntity wallet) {
        LedgerEntryJpaEntity e = new LedgerEntryJpaEntity();
        e.setWalletId(l.walletId().value());
        e.setEntryType(l.entryType().name());
        e.setAmount(l.amount().amount());
        e.setBalanceAfter(l.balanceAfter().amount());
        if (l.reference() != null) {
            e.setReferenceType(l.reference().type().name());
            e.setReferenceId(l.reference().id());
        }
        e.setTraceId(l.traceId());
        e.setOccurredAt(l.occurredAt());
        return e;
    }

    public static LedgerEntry toDomain(LedgerEntryJpaEntity e, Currency currency) {
        Reference ref = e.getReferenceType() != null
                ? new Reference(Reference.Type.valueOf(e.getReferenceType()), e.getReferenceId())
                : null;
        return new LedgerEntry(
                new WalletId(e.getWalletId()),
                LedgerEntry.EntryType.valueOf(e.getEntryType()),
                Money.of(e.getAmount(), currency),
                Money.of(e.getBalanceAfter(), currency),
                ref,
                e.getTraceId(),
                e.getOccurredAt()
        );
    }
}
