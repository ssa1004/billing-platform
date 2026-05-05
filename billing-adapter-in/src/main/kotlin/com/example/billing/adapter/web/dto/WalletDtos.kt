package com.example.billing.adapter.web.dto

import com.example.billing.domain.ledger.LedgerEntry
import com.example.billing.domain.wallet.Wallet
import java.math.BigDecimal
import java.time.Instant

data class WalletResponse(
    val id: String,
    val ownerId: String,
    val currency: String,
    val balance: BigDecimal,
    val blocked: BigDecimal,
    val available: BigDecimal,
    val updatedAt: Instant,
) {
    companion object {
        fun from(w: Wallet): WalletResponse = WalletResponse(
            id = w.id().toString(),
            ownerId = w.ownerId(),
            currency = w.currency().currencyCode,
            balance = w.balance().amount(),
            blocked = w.blocked().amount(),
            available = w.available().amount(),
            updatedAt = w.updatedAt(),
        )
    }
}

data class TransactionResponse(
    val entryType: String,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val referenceType: String?,
    val referenceId: String?,
    val traceId: String?,
    val occurredAt: Instant,
) {
    companion object {
        fun from(l: LedgerEntry): TransactionResponse = TransactionResponse(
            entryType = l.entryType().name,
            amount = l.amount().amount(),
            balanceAfter = l.balanceAfter().amount(),
            referenceType = l.reference()?.type()?.name,
            referenceId = l.reference()?.id(),
            traceId = l.traceId(),
            occurredAt = l.occurredAt(),
        )
    }
}
