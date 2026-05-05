package com.example.wallet.adapter.out.persistence.jpa.repository;

import com.example.wallet.adapter.out.persistence.jpa.entity.LedgerEntryJpaEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface SpringDataLedgerRepository extends JpaRepository<LedgerEntryJpaEntity, Long> {

    @Query("SELECT l FROM LedgerEntryJpaEntity l WHERE l.walletId = :walletId ORDER BY l.occurredAt DESC")
    List<LedgerEntryJpaEntity> findByWalletId(UUID walletId, PageRequest pageRequest);
}
