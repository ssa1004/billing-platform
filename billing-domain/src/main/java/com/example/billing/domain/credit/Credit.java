package com.example.billing.domain.credit;

import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import com.example.billing.domain.shared.Reference;

import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;

/**
 * Credit 애그리거트 — 청구서 결제 직전에 적용되는 선불 / 프로모성 잔액.
 *
 * <p><b>Wallet 과 무엇이 다른가</b> (별도 도메인으로 둔 이유):
 * <ul>
 *   <li>{@code Wallet} 은 거래 잔액 (입금 / 출금 / 블록). 사용자가 충전한 돈이라 환불 대상.</li>
 *   <li>{@code Credit} 은 발급된 잔액 (PROMO 쿠폰 / 보상 / 프로모션 등). 환불 불가, 만료
 *       가능, 청구서 결제 시 자동 차감.</li>
 * </ul>
 * 회계상 두 잔액의 수익 인식 (revenue recognition) 시점이 다름 — Wallet 은 충전 시점에
 * 부채로 잡고 사용 시점에 수익 전환, Credit 은 발급 시점에 마케팅 비용 / 보상 비용으로 잡힘.
 * 같은 테이블로 합치면 회계 기간말 마감에서 분리 비용이 더 큽니다.
 *
 * <p><b>Invariant (이 객체가 항상 만족해야 하는 규칙)</b>:
 * <ul>
 *   <li>{@code 0 <= balance <= grantedAmount} — 잔액은 음수가 될 수 없고 발급액을 초과할 수도
 *       없음</li>
 *   <li>차감 가능 조건: {@code status == ACTIVE} && {@code balance > 0}</li>
 *   <li>{@code validUntil != null && now >= validUntil} 이면 차감 거절 (status 가 EXPIRED 로
 *       자동 갱신되지 않은 시점에 차감 호출이 들어와도 도메인이 거절). 만료 처리는 batch 가
 *       명시적으로 호출 — ADR-0019 참조.</li>
 *   <li>모든 amount 는 이 Credit 의 {@code currency} 와 동일 (다른 통화는 호출자가 skip)</li>
 * </ul>
 *
 * <p><b>동시성</b>: {@code version} 으로 낙관적 락. 같은 Credit 을 동시에 차감하려 하거나
 * (동시에 여러 invoice 적용), 차감과 만료 batch 가 동시에 들어오는 경우 한쪽이
 * OptimisticLockException → application service 가 짧은 budget 안에서 재시도
 * ({@link com.example.billing.application.service.OptimisticLockRetry}).</p>
 *
 * <p><b>이벤트 발행 패턴</b>: 모든 상태 변경 메서드는 {@link CreditEvents} 의 record 를
 * 반환 합니다. 도메인이 직접 발행하지 않고 application service 가 받은 이벤트를 Outbox 에
 * INSERT — 도메인이 인프라 (DB / Kafka) 를 모르게 하기 위한 분리.</p>
 */
public final class Credit {

    private final CreditId id;
    private final CustomerId customerId;
    private final CreditType type;
    private final Currency currency;
    private final Money grantedAmount;
    private Money balance;
    private final Instant validFrom;
    private final Instant validUntil;   // null 이면 만료 없음 (PREPAID 일부 케이스)
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

    /** 영속 계층 (DB) 에서 읽어와 도메인 객체로 복원할 때만 호출 — 일반 코드는 grant() 사용. */
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
     * 잔액 차감. 잔액 부족 / 만료 / 비활성 상태면 예외 발생.
     * 차감 후 잔액이 0 이 되면 status 가 EXHAUSTED 로 자동 전이됩니다.
     *
     * @return 항상 {@link CreditEvents.CreditConsumed}. EXHAUSTED 로 전이됐는지 여부는
     *         status 를 직접 확인하면 됨.
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
     * 만료 처리 (batch 가 호출). 이미 종착 상태면 아무것도 하지 않고 null 반환.
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
