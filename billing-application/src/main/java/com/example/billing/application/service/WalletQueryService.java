package com.example.billing.application.service;

import com.example.billing.application.exception.WalletNotFoundException;
import com.example.billing.application.port.in.WalletQueryUseCase;
import com.example.billing.application.port.out.LedgerRepository;
import com.example.billing.application.port.out.WalletRepository;
import com.example.billing.domain.ledger.LedgerEntry;
import com.example.billing.domain.wallet.Wallet;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WalletQueryService implements WalletQueryUseCase {

    private final WalletRepository wallets;
    private final LedgerRepository ledger;

    @Override
    @Cacheable(cacheNames = "wallets", key = "#ownerId")
    @Transactional(readOnly = true)
    public Wallet getByOwner(String ownerId) {
        return wallets.findByOwnerId(ownerId)
                .orElseThrow(() -> new WalletNotFoundException(ownerId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerEntry> recentTransactions(String ownerId, int limit) {
        Wallet wallet = getByOwner(ownerId);
        return ledger.findByWallet(wallet.id(), limit);
    }
}
