package com.example.wallet.domain.ledger;

import com.example.wallet.domain.shared.Money;
import com.example.wallet.domain.shared.Reference;
import com.example.wallet.domain.wallet.WalletId;

import java.time.Instant;

/**
 * Append-only 거래 원장 항목. Wallet 잔액의 진실은 원장 합계 — 데이터 손상 시 재구성 가능 (정산 배치 가능).
 *
 * <p>{@code amount} 는 signed: positive = credit, negative = debit. {@code balanceAfter} 는 스냅샷.</p>
 */
public record LedgerEntry(
        WalletId walletId,
        EntryType entryType,
        Money amount,           // signed
        Money balanceAfter,
        Reference reference,
        String traceId,
        Instant occurredAt
) {
    public enum EntryType {
        DEPOSIT,        // + balance 증가
        WITHDRAWAL,     // - balance 감소
        REFUND,         // + 환불로 인한 환원
        ADJUSTMENT,     // 운영자 수동 조정
        BLOCK,          // (잔액 변화 없음, blocked 만 변동)
        UNBLOCK
    }

    public static LedgerEntry deposit(WalletId walletId, Money amount, Money balanceAfter,
                                      Reference reference, String traceId, Instant occurredAt) {
        if (!amount.isPositive()) throw new IllegalArgumentException("deposit amount must be positive");
        return new LedgerEntry(walletId, EntryType.DEPOSIT, amount, balanceAfter, reference, traceId, occurredAt);
    }

    public static LedgerEntry withdrawal(WalletId walletId, Money amount, Money balanceAfter,
                                         Reference reference, String traceId, Instant occurredAt) {
        if (!amount.isPositive()) throw new IllegalArgumentException("withdrawal amount must be positive");
        return new LedgerEntry(walletId, EntryType.WITHDRAWAL, amount.negate(), balanceAfter, reference, traceId, occurredAt);
    }

    public static LedgerEntry refund(WalletId walletId, Money amount, Money balanceAfter,
                                     Reference reference, String traceId, Instant occurredAt) {
        if (!amount.isPositive()) throw new IllegalArgumentException("refund amount must be positive");
        return new LedgerEntry(walletId, EntryType.REFUND, amount, balanceAfter, reference, traceId, occurredAt);
    }
}
