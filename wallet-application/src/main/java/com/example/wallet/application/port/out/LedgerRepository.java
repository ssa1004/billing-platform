package com.example.wallet.application.port.out;

import com.example.wallet.domain.ledger.LedgerEntry;
import com.example.wallet.domain.wallet.WalletId;

import java.util.List;

public interface LedgerRepository {
    void append(LedgerEntry entry);
    List<LedgerEntry> findByWallet(WalletId walletId, int limit);
}
