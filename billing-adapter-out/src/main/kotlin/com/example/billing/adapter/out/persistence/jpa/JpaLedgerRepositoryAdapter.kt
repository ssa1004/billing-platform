package com.example.billing.adapter.out.persistence.jpa

import com.example.billing.adapter.out.persistence.jpa.mapper.LedgerJpaMapper
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataLedgerRepository
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataWalletRepository
import com.example.billing.application.port.out.LedgerRepository
import com.example.billing.domain.ledger.LedgerEntry
import com.example.billing.domain.wallet.WalletId
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.util.Currency

@Repository
class JpaLedgerRepositoryAdapter(
    private val jpa: SpringDataLedgerRepository,
    private val walletJpa: SpringDataWalletRepository,
) : LedgerRepository {

    override fun append(entry: LedgerEntry) {
        // wallet entity 는 currency 조회용 (read), 매핑은 entry 단독으로 OK
        val wallet = walletJpa.findById(entry.walletId.value).orElseThrow()
        jpa.save(LedgerJpaMapper.toEntity(entry, wallet))
    }

    override fun findByWallet(walletId: WalletId, limit: Int): List<LedgerEntry> {
        val currency = walletJpa.findById(walletId.value)
            .map { Currency.getInstance(it.currency) }
            .orElseThrow()
        return jpa.findByWalletId(walletId.value, PageRequest.of(0, limit))
            .map { LedgerJpaMapper.toDomain(it, currency) }
    }
}
