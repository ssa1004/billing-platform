package com.example.wallet.application.service;

import com.example.wallet.application.exception.WalletNotFoundException;
import com.example.wallet.application.port.in.WalletQueryUseCase;
import com.example.wallet.application.port.out.LedgerRepository;
import com.example.wallet.application.port.out.WalletRepository;
import com.example.wallet.domain.ledger.LedgerEntry;
import com.example.wallet.domain.wallet.Wallet;
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
