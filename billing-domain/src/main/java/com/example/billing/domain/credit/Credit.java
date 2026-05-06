package com.example.billing.domain.credit;

import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import com.example.billing.domain.shared.Reference;

import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;

/**
 * Credit 애그리거트 — 청구서 차감 전 적용되는 선불/프로모성 잔액.
 *
 * <p><b>Wallet 과의 차이</b>:
 * <ul>
 *   <li>{@code Wallet} 은 거래 잔액 (입금/출금/블록). 사용자가 충전한 돈이며 환불 가능.</li>
 *   <li>{@code Credit} 은 *발급된* 잔액 (PROMO / COMPENSATION 등). 환불 대상 아님,
 *       만료 가능, 청구서에 자동 적용.</li>
 * </ul>
 * 회계상 분리 보관해야 하는 사유 (수익 인식 시점 다름) 도 있어 같은 테이블로 합치지 않는다.
 *
 * <p><b>Invariant</b>:
 * <ul>
 *   <li>{@code 0 <= balance <= grantedAmount}</li>
 *   <li>{@code balance > 0 && status == ACTIVE} 일 때만 차감 가능</li>
 *   <li>{@code validUntil != null && now > validUntil} → 차감 불가 (status 자동 EXPIRED 가 아니면 호출자 책임)</li>
 *   <li>모든 amount 는 {@code currency} 와 동일</li>
 * </ul>
 *
 * <p><b>동시성</b>: {@code version} 으로 낙관적 락. 동일 Credit 동시 차감 시 한쪽 OptimisticLock
 * → application service 가 retry. 만료 처리 batch 와 충돌도 같은 매커니즘으로 보호.</p>
 *
 * <p><b>이벤트</b>: 모든 상태 변경 메서드는 {@link CreditEvents} 의 record 를 반환.
 * application service 가 Outbox 에 기록.</p>
 */
public final class Credit {

    private final CreditId id;
    private final CustomerId customerId;
    private final CreditType type;
    private final Currency currency;
    private final Money grantedAmount;
    private Money balance;
    private final Instant validFrom;
    private final Instant validUntil;   // nullable = 만료 없음 (PREPAID 일부 케이스)
    private CreditStatus status;
    private final String reason;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private Credit(CreditId id, CustomerId customerId, CreditType type, Currency currency,
                   Money grantedAmount, Money balance,
                   Instant validFrom, Instant validUntil,
                   CreditStatus status, String reason,
                   Instant createdAt, Instant updatedAt, long version) {
        this.id = id;
        this.customerId = customerId;
        this.type = type;
        this.currency = currency;
        this.grantedAmount = grantedAmount;
        this.balance = balance;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.status = status;
        this.reason = reason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    /**
     * 신규 발급. {@code validUntil = null} 이면 만료 없음.
     */
    public static Credit grant(CustomerId customerId,
                               CreditType type,
                               Money amount,
                               Instant validFrom,
                               Instant validUntil,
                               String reason,
                               Clock clock) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(validFrom, "validFrom");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("granted amount must be positive: " + amount);
        }
        if (validUntil != null && !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
        Instant now = clock.instant();
        Credit c = new Credit(
                CreditId.newId(), customerId, type, amount.currency(),
                amount, amount,
                validFrom, validUntil,
                CreditStatus.ACTIVE, reason,
                now, now, 0L
        );
        c.checkInvariants();
        return c;
    }

    /** 영속 계층에서만 호출 — 외부에서 생성자 우회용. */
    public static Credit restore(CreditId id, CustomerId customerId, CreditType type, Currency currency,
                                 Money grantedAmount, Money balance,
                                 Instant validFrom, Instant validUntil,
                                 CreditStatus status, String reason,
                                 Instant createdAt, Instant updatedAt, long version) {
        Credit c = new Credit(id, customerId, type, currency, grantedAmount, balance,
                validFrom, validUntil, status, reason, createdAt, updatedAt, version);
        c.checkInvariants();
        return c;
    }

    /**
     * 차감. 잔액 부족 / 만료 / 비활성 상태면 throw.
     * 차감 후 잔액이 0 이 되면 EXHAUSTED 로 자동 전이.
     *
     * @return 항상 {@link CreditEvents.CreditConsumed}. 추가로 EXHAUSTED 로 전이됐다면
     *         {@link #lastExhaustedEvent} 로 확인 가능 (단순화 위해 별도 메서드).
     */
    public CreditEvents.CreditConsumed consume(Money amount, Reference reference, Clock clock) {
        ensureSameCurrency(amount);
        ensurePositive(amount);
        if (status != CreditStatus.ACTIVE) {
            throw new IllegalStateException("credit not active: status=" + status + " id=" + id);
        }
        Instant now = clock.instant();
        if (validFrom.isAfter(now)) {
            throw new IllegalStateException("credit not yet valid: validFrom=" + validFrom);
        }
        if (validUntil != null && !now.isBefore(validUntil)) {
            throw new IllegalStateException("credit already expired: validUntil=" + validUntil);
        }
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientCreditException(id, amount, balance);
        }
        this.balance = balance.subtract(amount);
        this.updatedAt = now;
        if (balance.isZero()) {
            this.status = CreditStatus.EXHAUSTED;
        }
        checkInvariants();
        return new CreditEvents.CreditConsumed(id, customerId, amount, balance, reference, now);
    }

    /**
     * 만료 처리 (batch). 이미 종착 상태면 no-op (null 반환).
     */
    public CreditEvents.CreditExpired expire(Clock clock) {
        if (status != CreditStatus.ACTIVE) return null;
        if (validUntil == null) {
            throw new IllegalStateException("non-expiring credit cannot be expired: id=" + id);
        }
        Instant now = clock.instant();
        if (now.isBefore(validUntil)) {
            throw new IllegalStateException("validUntil not reached yet: validUntil=" + validUntil + " now=" + now);
        }
        Money forfeited = balance;
        this.balance = Money.zero(currency);
        this.status = CreditStatus.EXPIRED;
        this.updatedAt = now;
        checkInvariants();
        return new CreditEvents.CreditExpired(id, customerId, forfeited, now);
    }

    /**
     * 운영자 강제 회수 (사기/오류 정정 등). 잔액 회수.
     */
    public CreditEvents.CreditRevoked revoke(String reason, Clock clock) {
        if (status != CreditStatus.ACTIVE) {
            throw new IllegalStateException("only ACTIVE credit can be revoked: status=" + status);
        }
        Money revoked = balance;
        this.balance = Money.zero(currency);
        this.status = CreditStatus.REVOKED;
        this.updatedAt = clock.instant();
        checkInvariants();
        return new CreditEvents.CreditRevoked(id, customerId, revoked, reason, this.updatedAt);
    }

    /** 현재 시점에 차감에 사용 가능한지. */
    public boolean isUsableAt(Instant now) {
        if (status != CreditStatus.ACTIVE) return false;
        if (validFrom.isAfter(now)) return false;
        if (validUntil != null && !now.isBefore(validUntil)) return false;
        return balance.isPositive();
    }

    private void ensureSameCurrency(Money amount) {
        if (!amount.currency().equals(currency)) {
            throw new IllegalArgumentException(
                    "currency mismatch: credit=" + currency + " amount=" + amount.currency());
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
        if (balance.compareTo(grantedAmount) > 0) {
            throw new IllegalStateException(
                    "invariant violation: balance > grantedAmount (balance=" + balance + " granted=" + grantedAmount + ")");
        }
    }

    // Getters
    public CreditId id() { return id; }
    public CustomerId customerId() { return customerId; }
    public CreditType type() { return type; }
    public Currency currency() { return currency; }
    public Money grantedAmount() { return grantedAmount; }
    public Money balance() { return balance; }
    public Instant validFrom() { return validFrom; }
    public Instant validUntil() { return validUntil; }
    public CreditStatus status() { return status; }
    public String reason() { return reason; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
