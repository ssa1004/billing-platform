package com.example.billing.domain.wallet

import com.example.billing.domain.shared.Money

/**
 * Wallet 잔액 부족 예외. 도메인이 직접 발행하므로 data class 가 아닌 일반 class.
 * `@get:JvmName` 으로 record-style accessor (`walletId()` / `requested()` / `available()`)
 * 보존 — Java 호출자 (RefundService, WalletQueryService 등) 무변경 동작.
 */
class InsufficientBalanceException(
    @get:JvmName("walletId") val walletId: WalletId,
    @get:JvmName("requested") val requested: Money,
    @get:JvmName("available") val available: Money,
) : RuntimeException(
    "insufficient balance: walletId=$walletId requested=$requested available=$available",
)
