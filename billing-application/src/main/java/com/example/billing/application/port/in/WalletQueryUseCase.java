package com.example.billing.application.port.in;

import com.example.billing.domain.ledger.LedgerEntry;
import com.example.billing.domain.wallet.Wallet;

import java.util.List;

public interface WalletQueryUseCase {
    Wallet getByOwner(String ownerId);
    List<LedgerEntry> recentTransactions(String ownerId, int limit);
}
