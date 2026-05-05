package com.example.wallet.domain.wallet;

import com.example.wallet.domain.shared.Money;

public class InsufficientBalanceException extends RuntimeException {
    private final WalletId walletId;
    private final Money requested;
    private final Money available;

    public InsufficientBalanceException(WalletId walletId, Money requested, Money available) {
        super("insufficient balance: walletId=" + walletId + " requested=" + requested + " available=" + available);
        this.walletId = walletId;
        this.requested = requested;
        this.available = available;
    }

    public WalletId walletId() { return walletId; }
    public Money requested() { return requested; }
    public Money available() { return available; }
}
