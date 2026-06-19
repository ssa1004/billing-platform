package com.example.billing.domain.shared

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.math.BigDecimal
import java.util.Currency
import org.junit.jupiter.api.Test

/**
 * [Money] 의 invariant 중 기존 Java `MoneyTest` 가 비워 둔 부분:
 * - 세 가지 `of(...)` factory (Long / String / BigDecimal) 동치성
 * - KRW 외 scale 0 통화 (JPY) 정규화
 * - subtract / compareTo 의 cross-currency 가드
 * - signum 헬퍼 (isPositive / isNegative / isZero / isNonNegative) 경계
 */
class MoneyInvariantTest {

    private val krw: Currency = Currency.getInstance("KRW")
    private val usd: Currency = Currency.getInstance("USD")
    private val jpy: Currency = Currency.getInstance("JPY")

    @Test
    fun `of Long String BigDecimal 은 같은 금액이면 동치다`() {
        val fromLong = Money.of(1000L, usd)
        val fromString = Money.of("1000", usd)
        val fromBigDecimal = Money.of(BigDecimal("1000.00"), usd)

        assertThat(fromLong).isEqualTo(fromString)
        assertThat(fromString).isEqualTo(fromBigDecimal)
        assertThat(fromLong.hashCode()).isEqualTo(fromBigDecimal.hashCode())
    }

    @Test
    fun `JPY 는 KRW 처럼 scale 0 으로 정규화된다`() {
        assertThat(Money.of("100.6", jpy).amount).isEqualByComparingTo("101")
        assertThat(Money.of("100.4", jpy).amount).isEqualByComparingTo("100")
    }

    @Test
    fun `subtract 도 다른 통화면 예외`() {
        assertThatThrownBy { Money.of(100L, krw).subtract(Money.of(1L, usd)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("currency mismatch")
    }

    @Test
    fun `compareTo 도 다른 통화면 예외`() {
        assertThatThrownBy { Money.of(100L, krw).compareTo(Money.of(1L, usd)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("currency mismatch")
    }

    @Test
    fun `signum 헬퍼는 0 경계에서 일관적이다`() {
        val zero = Money.zero(krw)
        val positive = Money.of(1L, krw)
        val negative = Money.of(-1L, krw)

        assertThat(zero.isZero).isTrue()
        assertThat(zero.isPositive).isFalse()
        assertThat(zero.isNegative).isFalse()
        assertThat(zero.isNonNegative).isTrue()

        assertThat(positive.isPositive).isTrue()
        assertThat(positive.isNonNegative).isTrue()

        assertThat(negative.isNegative).isTrue()
        assertThat(negative.isNonNegative).isFalse()
    }

    @Test
    fun `equals 는 통화가 다르면 금액이 같아도 false`() {
        // KRW 100 vs USD 100 — 값은 같지만 통화가 다름. (단, USD 는 scale 2)
        assertThat(Money.of(100L, krw)).isNotEqualTo(Money.of(100L, usd))
    }
}
