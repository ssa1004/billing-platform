package com.example.billing.application.port.out;

import com.example.billing.domain.ledger.LedgerEntry;
import com.example.billing.domain.wallet.WalletId;

import java.util.List;

public interface LedgerRepository {
    void append(LedgerEntry entry);
    List<LedgerEntry> findByWallet(WalletId walletId, int limit);
}
