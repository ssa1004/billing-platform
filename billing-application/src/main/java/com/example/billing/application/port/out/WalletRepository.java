package com.example.billing.application.port.out;

import com.example.billing.domain.wallet.Wallet;
import com.example.billing.domain.wallet.WalletId;

import java.util.Optional;

public interface WalletRepository {

    void save(Wallet wallet);

    Optional<Wallet> findById(WalletId id);

    Optional<Wallet> findByOwnerId(String ownerId);
}
