package com.example.billing.domain.wallet;

import com.example.billing.domain.shared.Money;
import com.example.billing.domain.shared.Reference;

import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;

/**
 * Wallet 애그리거트 루트 (한 트랜잭션으로 같이 저장되는 도메인 객체 묶음의 진입점).
 *
 * <p><b>도메인 invariant (항상 만족해야 하는 규칙, 도메인 메서드 외 변경 금지)</b>:</p>
 * <ul>
 *   <li>{@code balance >= 0} (음수 잔액 금지)</li>
 *   <li>{@code blocked >= 0}</li>
 *   <li>{@code blocked <= balance} (블록한 금액은 잔액 안에 있어야 함)</li>
 *   <li>모든 amount 는 wallet.currency 와 동일</li>
 * </ul>
 *
 * <p><b>동시성</b>: {@code version} 필드로 낙관적 락 (충돌이 드물다고 가정하고 일단 처리한
 * 뒤, 충돌 시 예외 후 재시도). 동시 차감 시 한쪽은 OptimisticLockException → 클라이언트가
 * retry. 강한 직렬화가 필요하면 Postgres advisory lock 으로 보강 가능 (ADR-0007).</p>
 *
 * <p><b>이벤트</b>: 모든 잔액 변경은 {@link WalletEvents} 를 반환합니다 → 호출자
 * (Application service) 가 Outbox 에 기록 + Ledger 작성.</p>
 */
public class Wallet {

    private final WalletId id;
    private final String ownerId;
    private final Currency currency;
    private Money balance;
    private Money blocked;
    private Instant createdAt;
    private Instant updatedAt;
    private long version;

    private Wallet(WalletId id, String ownerId, Currency currency,
                   Money balance, Money blocked, Instant createdAt, Instant updatedAt, long version) {
        this.id = id;
        this.ownerId = ownerId;
        this.currency = currency;
        this.balance = balance;
        this.blocked = blocked;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    /** 신규 Wallet 개설 (잔액 0). */
    public static Wallet open(String ownerId, Currency currency, Clock clock) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(currency, "currency");
        Instant now = clock.instant();
        return new Wallet(
                WalletId.newId(),
                ownerId,
                currency,
                Money.zero(currency),
                Money.zero(currency),
                now,
                now,
                0L
        );
    }

    /** 영속 계층 (DB 등) 에서 읽어와 도메인 객체로 복원 — 외부에서만 호출. */
    public static Wallet restore(WalletId id, String ownerId, Currency currency,
                                 Money balance, Money blocked, Instant createdAt, Instant updatedAt, long version) {
        Wallet w = new Wallet(id, ownerId, currency, balance, blocked, createdAt, updatedAt, version);
        w.checkInvariants();
        return w;
    }

    public WalletEvents.WalletDeposited deposit(Money amount, Reference reference, Clock clock) {
        ensureSameCurrency(amount);
        ensurePositive(amount);
        this.balance = balance.add(amount);
        this.updatedAt = clock.instant();
        checkInvariants();
        return new WalletEvents.WalletDeposited(id, amount, balance, reference, updatedAt);
    }

    public WalletEvents.WalletWithdrawn withdraw(Money amount, Reference reference, Clock clock) {
        ensureSameCurrency(amount);
        ensurePositive(amount);
        Money available = balance.subtract(blocked);
        if (available.compareTo(amount) < 0) {
            throw new InsufficientBalanceException(id, amount, available);
        }
        this.balance = balance.subtract(amount);
        this.updatedAt = clock.instant();
        checkInvariants();
        return new WalletEvents.WalletWithdrawn(id, amount, balance, reference, updatedAt);
    }

    public WalletEvents.WalletBlocked block(Money amount, Reference reference, Clock clock) {
        ensureSameCurrency(amount);
        ensurePositive(amount);
        Money available = balance.subtract(blocked);
        if (available.compareTo(amount) < 0) {
            throw new InsufficientBalanceException(id, amount, available);
        }
        this.blocked = blocked.add(amount);
        this.updatedAt = clock.instant();
        checkInvariants();
        return new WalletEvents.WalletBlocked(id, amount, blocked, reference, updatedAt);
    }

    public WalletEvents.WalletUnblocked unblock(Money amount, Reference reference, Clock clock) {
        ensureSameCurrency(amount);
        ensurePositive(amount);
        if (blocked.compareTo(amount) < 0) {
            throw new IllegalStateException(
                    "cannot unblock more than blocked: blocked=" + blocked + " requested=" + amount);
        }
        this.blocked = blocked.subtract(amount);
        this.updatedAt = clock.instant();
        checkInvariants();
        return new WalletEvents.WalletUnblocked(id, amount, blocked, reference, updatedAt);
    }

    public Money available() { return balance.subtract(blocked); }

    private void ensureSameCurrency(Money amount) {
        if (!amount.currency().equals(this.currency)) {
            throw new IllegalArgumentException(
                    "currency mismatch: wallet=" + currency + " amount=" + amount.currency());
        }
    }

    private static void ensurePositive(Money amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
    }

    private void checkInvariants() {
        if (balance.isNegative()) {
            throw new IllegalStateException("invariant violation: balance < 0 (" + balance + ")");
        }
        if (blocked.isNegative()) {
            throw new IllegalStateException("invariant violation: blocked < 0 (" + blocked + ")");
        }
        if (blocked.compareTo(balance) > 0) {
            throw new IllegalStateException(
                    "invariant violation: blocked > balance (blocked=" + blocked + " balance=" + balance + ")");
        }
    }

    // Getters
    public WalletId id() { return id; }
    public String ownerId() { return ownerId; }
    public Currency currency() { return currency; }
    public Money balance() { return balance; }
    public Money blocked() { return blocked; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
