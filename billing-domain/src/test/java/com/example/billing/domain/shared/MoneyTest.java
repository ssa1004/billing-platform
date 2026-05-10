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
    void multiply_HALF_UP_으로_currency_scale_에_반올림() {
        // USD scale 2: 100.005 → 100.01 (HALF_UP)
        Money price = Money.of("100.00", USD);
        Money result = price.multiply(new BigDecimal("1.00005"));
        assertThat(result.amount()).isEqualByComparingTo("100.01");
    }

    @Test
    void multiply_negative_factor_도_HALF_UP_부호_대칭() {
        // -0.5 → -1 (away from zero), KRW
        Money price = Money.of(BigDecimal.ONE, KRW);
        assertThat(price.multiply(new BigDecimal("-0.5")).amount()).isEqualByComparingTo("-1");
        assertThat(price.multiply(new BigDecimal("0.5")).amount()).isEqualByComparingTo("1");
    }

    @Test
    void multiply_KRW_factor_분수_HALF_UP() {
        // 100원 × 0.105 = 10.5 → 11 (HALF_UP), 100원 × 0.104 = 10.4 → 10
        Money won = Money.of(100L, KRW);
        assertThat(won.multiply(new BigDecimal("0.105")).amount()).isEqualByComparingTo("11");
        assertThat(won.multiply(new BigDecimal("0.104")).amount()).isEqualByComparingTo("10");
    }

    @Test
    void add_subtract_는_currency_scale_끼리의_연산이라_정밀도_손실_없음() {
        // 같은 currency scale 끼리의 산술은 결과도 같은 scale → 자명한 항등
        Money a = Money.of("0.01", USD);
        Money sum = Money.zero(USD);
        for (int i = 0; i < 1000; i++) sum = sum.add(a);   // 0.01 × 1000 = 10.00
        assertThat(sum.amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void multiply_line_별_round_이_sum_then_round_와_달라질_수_있다() {
        // javadoc 의 다중 line × fractional rate 경고를 락다운.
        // line 별: round(0.5) + round(0.5) + round(0.5) = 1 + 1 + 1 = 3 (HALF_UP)
        // sum-then: round(0.5 + 0.5 + 0.5) = round(1.5) = 2 (HALF_UP)
        Money won = Money.of(1L, KRW);
        Money perLine = won.multiply(new BigDecimal("0.5"));
        Money summed = perLine.add(perLine).add(perLine);
        assertThat(summed.amount()).isEqualByComparingTo("3");

        Money sumThenRound = won.add(won).add(won).multiply(new BigDecimal("0.5"));
        assertThat(sumThenRound.amount()).isEqualByComparingTo("2");
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
