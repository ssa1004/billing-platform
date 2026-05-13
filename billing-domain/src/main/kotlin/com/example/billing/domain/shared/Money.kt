package com.example.billing.domain.shared

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency
import java.util.Objects

/**
 * 통화 (Currency) 와 금액 (BigDecimal) 을 함께 들고 다니는 Value Object (immutable, 한 번
 * 만들어지면 절대 안 바뀜).
 *
 * 도메인 invariant (이 클래스가 항상 보장하는 규칙):
 * - **다른 통화 간 산술 금지** — 예: USD + KRW → 즉시 예외. 환율 변환은 별도 도메인의
 *   책임이라 Money 는 같은 통화끼리만 더하고 뺍니다.
 * - **scale (소수점 자릿수) 자동 정규화** — 통화별 minor unit 에 맞춰 BigDecimal 의 scale 을
 *   강제. KRW 는 정수 (scale=0), USD 는 센트 단위 (scale=2), JPY 는 0 등. 같은 금액이 scale
 *   만 다른 두 객체로 표현되어 비교가 어그러지는 일을 방지.
 * - **음수 허용** — 회계 원장 (ledger) 의 차변/대변 (debit/credit) 표기에 음수가 필요하기
 *   때문. 입금은 +amount, 출금은 -amount 식으로 한 컬럼에 부호로 구분.
 *
 * **반올림 정책 (rounding contract)**: 모든 산술 결과는 생성자에서 [RoundingMode.HALF_UP] 으로
 * 통화의 minor unit scale 에 맞춰 즉시 정규화됩니다. HALF_UP 은 절댓값 기준 반올림 (away from
 * zero) 이라 결제 0.5 → 1, 환불 -0.5 → -1 처럼 부호 대칭으로 동작합니다.
 *
 * 주의 — [add]/[subtract] 는 같은 currency scale 끼리의 연산이라 정밀도 손실이 없지만,
 * [multiply] 의 factor 가 fractional 이면 결과가 currency scale 로 반올림 되어 line 별 round →
 * sum 과 sum → round 결과가 달라질 수 있습니다. 다중 line 에 fractional rate (예: 8.875% sales
 * tax) 를 적용할 때는 line 별 곱하기보다 전체 합산 후 한 번에 곱하는 쪽을 권장합니다.
 *
 * **Wallet balance 의 "음수 금지" 는 어디서?** Money 자체가 음수를 허용해도 잔액 무결성은
 * [com.example.billing.domain.wallet.Wallet] 가 보장합니다 — Money 는 산술 단위일 뿐, 도메인
 * invariant 는 그 단위를 사용하는 애그리거트의 책임입니다.
 *
 * **Kotlin 변환 노트**: data class 가 아닌 일반 class — BigDecimal 의 scale 차이 (예: `10.0` vs
 * `10.00`) 을 같은 금액으로 본다는 의미를 보존하려면 [equals] 가 `compareTo == 0` 으로 동작하고
 * [hashCode] 가 `stripTrailingZeros` 결과를 기반으로 해야 한다. data class 의 자동 생성 equals
 * (BigDecimal.equals 가 scale 까지 본다) 와 hashCode 가 이 의미를 깨므로 직접 작성.
 */
class Money private constructor(
    amount: BigDecimal,
    currency: Currency,
) : Comparable<Money> {

    @get:JvmName("amount")
    val amount: BigDecimal = amount.setScale(currency.defaultFractionDigits, RoundingMode.HALF_UP)

    @get:JvmName("currency")
    val currency: Currency = currency

    fun add(other: Money): Money {
        ensureSameCurrency(other)
        return Money(this.amount.add(other.amount), this.currency)
    }

    fun subtract(other: Money): Money {
        ensureSameCurrency(other)
        return Money(this.amount.subtract(other.amount), this.currency)
    }

    fun multiply(factor: BigDecimal): Money = Money(this.amount.multiply(factor), this.currency)

    fun negate(): Money = Money(this.amount.negate(), this.currency)

    @get:JvmName("isPositive")
    val isPositive: Boolean get() = amount.signum() > 0

    @get:JvmName("isNegative")
    val isNegative: Boolean get() = amount.signum() < 0

    @get:JvmName("isZero")
    val isZero: Boolean get() = amount.signum() == 0

    @get:JvmName("isNonNegative")
    val isNonNegative: Boolean get() = amount.signum() >= 0

    override fun compareTo(other: Money): Int {
        ensureSameCurrency(other)
        return this.amount.compareTo(other.amount)
    }

    private fun ensureSameCurrency(other: Money) {
        require(this.currency == other.currency) {
            "currency mismatch: ${this.currency} vs ${other.currency}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Money) return false
        return amount.compareTo(other.amount) == 0 && currency == other.currency
    }

    override fun hashCode(): Int {
        // BigDecimal.equals 가 scale 다르면 false 라 stripTrailingZeros 사용
        return Objects.hash(amount.stripTrailingZeros(), currency)
    }

    override fun toString(): String = "$amount ${currency.currencyCode}"

    companion object {
        @JvmStatic
        fun of(amount: BigDecimal, currency: Currency): Money = Money(amount, currency)

        @JvmStatic
        fun of(amount: Long, currency: Currency): Money = Money(BigDecimal.valueOf(amount), currency)

        @JvmStatic
        fun of(amount: String, currency: Currency): Money = Money(BigDecimal(amount), currency)

        @JvmStatic
        fun zero(currency: Currency): Money = Money(BigDecimal.ZERO, currency)
    }
}
