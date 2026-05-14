package com.example.billing.domain.invoice

import com.example.billing.domain.shared.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Objects

/**
 * 세율 — 청구서 line 별 또는 전체에 적용. region / 고객 유형 / 상품 유형에 따라 다름.
 *
 * 예시:
 * - 한국 부가세: 10% (`TaxRate.of("KR_VAT", 10)`)
 * - EU VAT: 19~25% (국가별)
 * - 면세: 0% (`TaxRate.exempt()`)
 *
 * 실무에서는 외부 tax engine (Avalara, TaxJar 등) 으로 위임하는 경우가 많지만, 본 도메인은
 * single-rate per line 의 단순 모델로 시작. 복잡해지면 TaxEngine 인터페이스로 전환.
 *
 * **Kotlin 변환 노트**: data class 가 아닌 일반 class — BigDecimal 의 scale 차이 (예: `10` vs
 * `10.0`) 를 같은 세율로 본다는 의미를 보존하려면 [equals] 가 `compareTo == 0` 으로 동작해야
 * 한다. data class 의 자동 생성 equals (BigDecimal.equals 가 scale 까지 본다) 가 이 의미를
 * 깨므로 직접 작성. record-style accessor (`code()` / `percentage()`) 는 `@get:JvmName` 으로
 * Java 호출자 호환 유지.
 */
class TaxRate private constructor(
    code: String,
    percentage: BigDecimal,
) {

    @get:JvmName("code")
    val code: String

    @get:JvmName("percentage")
    val percentage: BigDecimal

    init {
        require(code.isNotBlank()) { "tax code must not be blank" }
        require(percentage.signum() >= 0) { "percentage must be non-negative" }
        require(percentage.compareTo(BigDecimal.valueOf(100)) <= 0) { "percentage must be ≤ 100" }
        this.code = code
        this.percentage = percentage
    }

    /** 주어진 net 금액에서 세금 액수 계산 (음수 금액도 허용 — 환불 사용). */
    fun taxOn(net: Money): Money {
        val factor = percentage.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
        return net.multiply(factor)
    }

    fun isExempt(): Boolean = percentage.signum() == 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TaxRate) return false
        return code == other.code && percentage.compareTo(other.percentage) == 0
    }

    override fun hashCode(): Int = Objects.hash(code, percentage)

    override fun toString(): String = "$code ($percentage%)"

    companion object {
        @JvmStatic
        fun of(code: String, percentage: Double): TaxRate =
            TaxRate(code, BigDecimal.valueOf(percentage))

        @JvmStatic
        fun of(code: String, percentage: BigDecimal): TaxRate = TaxRate(code, percentage)

        @JvmStatic
        fun exempt(): TaxRate = TaxRate("EXEMPT", BigDecimal.ZERO)

        @JvmStatic
        fun koreaVAT(): TaxRate = TaxRate("KR_VAT", BigDecimal.TEN)
    }
}
