package com.example.wallet.adapter.out.persistence.jpa;

import com.example.wallet.adapter.out.persistence.jpa.mapper.LedgerJpaMapper;
import com.example.wallet.adapter.out.persistence.jpa.repository.SpringDataLedgerRepository;
import com.example.wallet.adapter.out.persistence.jpa.repository.SpringDataWalletRepository;
import com.example.wallet.application.port.out.LedgerRepository;
import com.example.wallet.domain.ledger.LedgerEntry;
import com.example.wallet.domain.wallet.WalletId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.Currency;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class JpaLedgerRepositoryAdapter implements LedgerRepository {

    private final SpringDataLedgerRepository jpa;
    private final SpringDataWalletRepository walletJpa;

    @Override
    public void append(LedgerEntry entry) {
        // wallet entity 는 currency 조회용 (read), 매핑은 entry 단독으로 OK
        var wallet = walletJpa.findById(entry.walletId().value()).orElseThrow();
        jpa.save(LedgerJpaMapper.toEntity(entry, wallet));
    }

    @Override
    public List<LedgerEntry> findByWallet(WalletId walletId, int limit) {
        Currency currency = walletJpa.findById(walletId.value())
                .map(w -> Currency.getInstance(w.getCurrency()))
                .orElseThrow();
        return jpa.findByWalletId(walletId.value(), PageRequest.of(0, limit))
                .stream()
                .map(e -> LedgerJpaMapper.toDomain(e, currency))
                .toList();
    }
}
