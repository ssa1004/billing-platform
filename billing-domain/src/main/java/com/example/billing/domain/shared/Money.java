package com.example.billing.domain.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * 통화-감지 금액 Value Object (immutable).
 *
 * <p>도메인 invariant:</p>
 * <ul>
 *   <li>다른 통화 간 산술 금지 (예: USD + KRW → throw)</li>
 *   <li>scale 일관성 — 통화별 minor unit 에 맞춰 자동 정규화 (KRW=0, USD=2 등)</li>
 *   <li>음수 허용 (Ledger 의 debit/credit 표현용)</li>
 * </ul>
 *
 * <p>Ledger entry 는 signed Money (음수 = 출금) 를 사용. Wallet balance 는 항상 0 이상이어야 하지만
 * 그 invariant 는 {@link com.example.billing.domain.wallet.Wallet} 에서 강제 — Money 자체는 산술 단위.</p>
 */
public final class Money implements Comparable<Money> {

    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        this.amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP);
        this.currency = currency;
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money of(long amount, Currency currency) {
        return new Money(BigDecimal.valueOf(amount), currency);
    }

    public static Money of(String amount, Currency currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public BigDecimal amount() { return amount; }
    public Currency currency() { return currency; }

    public Money add(Money other) {
        ensureSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money subtract(Money other) {
        ensureSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.currency);
    }

    public Money multiply(BigDecimal factor) {
        return new Money(this.amount.multiply(factor), this.currency);
    }

    public Money negate() {
        return new Money(this.amount.negate(), this.currency);
    }

    public boolean isPositive() { return amount.signum() > 0; }
    public boolean isNegative() { return amount.signum() < 0; }
    public boolean isZero() { return amount.signum() == 0; }
    public boolean isNonNegative() { return amount.signum() >= 0; }

    @Override
    public int compareTo(Money other) {
        ensureSameCurrency(other);
        return this.amount.compareTo(other.amount);
    }

    private void ensureSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "currency mismatch: " + this.currency + " vs " + other.currency);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money m)) return false;
        return amount.compareTo(m.amount) == 0 && currency.equals(m.currency);
    }

    @Override
    public int hashCode() {
        // BigDecimal.equals 가 scale 다르면 false 라 stripTrailingZeros 사용
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return amount + " " + currency.getCurrencyCode();
    }
}
