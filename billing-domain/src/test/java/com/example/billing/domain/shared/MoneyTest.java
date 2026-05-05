package com.example.billing.domain.shared;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void of_normalizesScaleByCurrency() {
        // KRW = 0 fraction digits, USD = 2
        assertThat(Money.of("100.5", KRW).amount()).isEqualByComparingTo("101");
        assertThat(Money.of("100.555", USD).amount()).isEqualByComparingTo("100.56");
    }

    @Test
    void add_sameCurrency_works() {
        Money a = Money.of(1000, KRW);
        Money b = Money.of(500, KRW);
        assertThat(a.add(b)).isEqualTo(Money.of(1500, KRW));
    }

    @Test
    void add_differentCurrency_throws() {
        assertThatThrownBy(() -> Money.of(100, KRW).add(Money.of(1, USD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency mismatch");
    }

    @Test
    void subtract_canGoNegative() {
        Money result = Money.of(100, KRW).subtract(Money.of(150, KRW));
        assertThat(result.isNegative()).isTrue();
        assertThat(result.amount()).isEqualByComparingTo("-50");
    }

    @Test
    void multiply_works() {
        Money price = Money.of("100.50", USD);
        Money total = price.multiply(BigDecimal.valueOf(3));
        assertThat(total).isEqualTo(Money.of("301.50", USD));
    }

    @Test
    void compareTo_sameCurrency_works() {
        assertThat(Money.of(100, KRW).compareTo(Money.of(50, KRW))).isPositive();
        assertThat(Money.of(50, KRW).compareTo(Money.of(50, KRW))).isZero();
        assertThat(Money.of(50, KRW).compareTo(Money.of(100, KRW))).isNegative();
    }

    @Test
    void equals_sameValueDifferentScale_isEqual() {
        // BigDecimal("100") != BigDecimal("100.00") by .equals(), but Money.equals 가 보정
        Money a = Money.of(new BigDecimal("100"), USD);
        Money b = Money.of(new BigDecimal("100.00"), USD);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void zero_isZero() {
        assertThat(Money.zero(KRW).isZero()).isTrue();
    }

    @Test
    void negate_flipsSign() {
        assertThat(Money.of(100, KRW).negate()).isEqualTo(Money.of(-100, KRW));
    }
}
