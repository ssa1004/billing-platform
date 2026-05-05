package com.example.wallet.application.port.in;

import com.example.wallet.domain.ledger.LedgerEntry;
import com.example.wallet.domain.wallet.Wallet;

import java.util.List;

public interface WalletQueryUseCase {
    Wallet getByOwner(String ownerId);
    List<LedgerEntry> recentTransactions(String ownerId, int limit);
}
