package com.example.wallet.application.port.out;

import com.example.wallet.domain.wallet.Wallet;
import com.example.wallet.domain.wallet.WalletId;

import java.util.Optional;

public interface WalletRepository {

    void save(Wallet wallet);

    Optional<Wallet> findById(WalletId id);

    Optional<Wallet> findByOwnerId(String ownerId);
}
