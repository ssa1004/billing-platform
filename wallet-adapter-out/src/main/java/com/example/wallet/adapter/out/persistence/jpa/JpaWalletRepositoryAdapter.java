package com.example.wallet.adapter.out.persistence.jpa;

import com.example.wallet.adapter.out.persistence.jpa.entity.WalletJpaEntity;
import com.example.wallet.adapter.out.persistence.jpa.mapper.WalletJpaMapper;
import com.example.wallet.adapter.out.persistence.jpa.repository.SpringDataWalletRepository;
import com.example.wallet.application.port.out.WalletRepository;
import com.example.wallet.domain.wallet.Wallet;
import com.example.wallet.domain.wallet.WalletId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaWalletRepositoryAdapter implements WalletRepository {

    private final SpringDataWalletRepository jpa;

    @Override
    public void save(Wallet wallet) {
        // domain → entity. Wallet 의 version 필드를 그대로 보냄 → JPA 가 낙관적 락 검증
        WalletJpaEntity entity = WalletJpaMapper.toEntity(wallet);
        jpa.save(entity);
    }

    @Override
    public Optional<Wallet> findById(WalletId id) {
        return jpa.findById(id.value()).map(WalletJpaMapper::toDomain);
    }

    @Override
    public Optional<Wallet> findByOwnerId(String ownerId) {
        return jpa.findByOwnerId(ownerId).map(WalletJpaMapper::toDomain);
    }
}
