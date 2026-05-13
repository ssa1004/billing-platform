package com.example.billing.domain.ledger

import com.example.billing.domain.shared.Money
import com.example.billing.domain.shared.Reference
import com.example.billing.domain.wallet.WalletId
import java.time.Instant

/**
 * Append-only 거래 원장 항목. Wallet 잔액의 진실은 원장 합계 — 데이터 손상 시 재구성 가능
 * (정산 배치 가능).
 *
 * [amount] 는 signed: positive = credit, negative = debit. [balanceAfter] 는 스냅샷.
 *
 * Kotlin `@JvmRecord` 로 컴파일 — Java record 와 동일한 component accessor (`walletId()`,
 * `entryType()` 등) 을 노출해 호출자 호환성 (Java + Kotlin) 보존.
 */
@JvmRecord
data class LedgerEntry(
    val walletId: WalletId,
    val entryType: EntryType,
    /** signed: positive = credit, negative = debit. */
    val amount: Money,
    val balanceAfter: Money,
    val reference: Reference?,
    val traceId: String?,
    val occurredAt: Instant,
) {

    enum class EntryType {
        DEPOSIT,        // + balance 증가
        WITHDRAWAL,     // - balance 감소
        REFUND,         // + 환불로 인한 환원
        ADJUSTMENT,     // 운영자 수동 조정
        BLOCK,          // (잔액 변화 없음, blocked 만 변동)
        UNBLOCK,
    }

    companion object {
        @JvmStatic
        fun deposit(
            walletId: WalletId,
            amount: Money,
            balanceAfter: Money,
            reference: Reference?,
            traceId: String?,
            occurredAt: Instant,
        ): LedgerEntry {
            require(amount.isPositive) { "deposit amount must be positive" }
            return LedgerEntry(walletId, EntryType.DEPOSIT, amount, balanceAfter, reference, traceId, occurredAt)
        }

        @JvmStatic
        fun withdrawal(
            walletId: WalletId,
            amount: Money,
            balanceAfter: Money,
            reference: Reference?,
            traceId: String?,
            occurredAt: Instant,
        ): LedgerEntry {
            require(amount.isPositive) { "withdrawal amount must be positive" }
            return LedgerEntry(walletId, EntryType.WITHDRAWAL, amount.negate(), balanceAfter, reference, traceId, occurredAt)
        }

        @JvmStatic
        fun refund(
            walletId: WalletId,
            amount: Money,
            balanceAfter: Money,
            reference: Reference?,
            traceId: String?,
            occurredAt: Instant,
        ): LedgerEntry {
            require(amount.isPositive) { "refund amount must be positive" }
            return LedgerEntry(walletId, EntryType.REFUND, amount, balanceAfter, reference, traceId, occurredAt)
        }
    }
}
