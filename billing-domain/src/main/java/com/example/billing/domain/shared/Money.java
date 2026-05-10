package com.example.billing.domain.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * 통화 (Currency) 와 금액 (BigDecimal) 을 함께 들고 다니는 Value Object (immutable, 한 번
 * 만들어지면 절대 안 바뀜).
 *
 * <p>도메인 invariant (이 클래스가 항상 보장하는 규칙):</p>
 * <ul>
 *   <li><b>다른 통화 간 산술 금지</b> — 예: USD + KRW → 즉시 예외. 환율 변환은 별도 도메인의
 *       책임이라 Money 는 같은 통화끼리만 더하고 뺍니다.</li>
 *   <li><b>scale (소수점 자릿수) 자동 정규화</b> — 통화별 minor unit 에 맞춰 BigDecimal 의
 *       scale 을 강제. KRW 는 정수 (scale=0), USD 는 센트 단위 (scale=2), JPY 는 0 등. 같은
 *       금액이 scale 만 다른 두 객체로 표현되어 비교가 어그러지는 일을 방지.</li>
 *   <li><b>음수 허용</b> — 회계 원장 (ledger) 의 차변/대변 (debit/credit) 표기에 음수가
 *       필요하기 때문. 입금은 +amount, 출금은 -amount 식으로 한 컬럼에 부호로 구분.</li>
 * </ul>
 *
 * <p><b>반올림 정책 (rounding contract)</b>: 모든 산술 결과는 생성자에서
 * {@link RoundingMode#HALF_UP} 으로 통화의 minor unit scale 에 맞춰 즉시 정규화됩니다.
 * HALF_UP 은 절댓값 기준 반올림 (away from zero) 이라 결제 0.5 → 1, 환불 -0.5 → -1 처럼
 * 부호 대칭으로 동작합니다.</p>
 *
 * <p>주의 — {@link #add}/{@link #subtract} 는 같은 currency scale 끼리의 연산이라 정밀도
 * 손실이 없지만, {@link #multiply} 의 factor 가 fractional 이면 결과가 currency scale 로
 * 반올림 되어 line 별 round → sum 과 sum → round 결과가 달라질 수 있습니다. 다중 line 에
 * fractional rate (예: 8.875% sales tax) 를 적용할 때는 line 별 곱하기보다 전체 합산 후 한
 * 번에 곱하는 쪽을 권장합니다.</p>
 *
 * <p><b>Wallet balance 의 "음수 금지" 는 어디서?</b> Money 자체가 음수를 허용해도 잔액
 * 무결성은 {@link com.example.billing.domain.wallet.Wallet} 가 보장합니다 — Money 는 *산술
 * 단위* 일 뿐, 도메인 invariant 는 그 단위를 사용하는 애그리거트의 책임입니다.</p>
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
