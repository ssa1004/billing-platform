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
)

fun Wallet.toResponse(): WalletResponse = WalletResponse(
    id = id().toString(),
    ownerId = ownerId(),
    currency = currency().currencyCode,
    balance = balance().amount(),
    blocked = blocked().amount(),
    available = available().amount(),
    updatedAt = updatedAt(),
)

data class TransactionResponse(
    val entryType: String,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val referenceType: String?,
    val referenceId: String?,
    val traceId: String?,
    val occurredAt: Instant,
)

fun LedgerEntry.toResponse(): TransactionResponse = TransactionResponse(
    entryType = entryType().name,
    amount = amount().amount(),
    balanceAfter = balanceAfter().amount(),
    referenceType = reference()?.type()?.name,
    referenceId = reference()?.id(),
    traceId = traceId(),
    occurredAt = occurredAt(),
)
