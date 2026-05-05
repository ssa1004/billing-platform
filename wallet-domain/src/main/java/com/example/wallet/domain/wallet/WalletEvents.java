package com.example.wallet.domain.wallet;

import com.example.wallet.domain.shared.DomainEvent;
import com.example.wallet.domain.shared.Money;
import com.example.wallet.domain.shared.Reference;

import java.time.Instant;

/** Wallet 도메인 이벤트 sealed 트리. */
public sealed interface WalletEvents extends DomainEvent
        permits WalletEvents.WalletDeposited,
                WalletEvents.WalletWithdrawn,
                WalletEvents.WalletBlocked,
                WalletEvents.WalletUnblocked {

    record WalletDeposited(
            WalletId walletId,
            Money amount,
            Money balanceAfter,
            Reference reference,
            Instant occurredAt
    ) implements WalletEvents {
        @Override public String aggregateId() { return walletId.toString(); }
    }

    record WalletWithdrawn(
            WalletId walletId,
            Money amount,
            Money balanceAfter,
            Reference reference,
            Instant occurredAt
    ) implements WalletEvents {
        @Override public String aggregateId() { return walletId.toString(); }
    }

    record WalletBlocked(
            WalletId walletId,
            Money amount,
            Money blockedAfter,
            Reference reference,
            Instant occurredAt
    ) implements WalletEvents {
        @Override public String aggregateId() { return walletId.toString(); }
    }

    record WalletUnblocked(
            WalletId walletId,
            Money amount,
            Money blockedAfter,
            Reference reference,
            Instant occurredAt
    ) implements WalletEvents {
        @Override public String aggregateId() { return walletId.toString(); }
    }
}
