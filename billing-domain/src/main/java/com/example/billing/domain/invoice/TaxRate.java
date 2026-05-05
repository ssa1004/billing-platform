package com.example.billing.domain.invoice;

import com.example.billing.domain.shared.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 세율 — 청구서 line 별 또는 전체에 적용. region / 고객 유형 / 상품 유형에 따라 다름.
 *
 * <p>예시:
 * <ul>
 *   <li>한국 부가세: 10% (`TaxRate.of("KR_VAT", 10)`)</li>
 *   <li>EU VAT: 19~25% (국가별)</li>
 *   <li>면세: 0% (`TaxRate.exempt()`)</li>
 * </ul>
 *
 * <p>실무에서는 외부 tax engine (Avalara, TaxJar 등) 으로 위임하는 경우가 많지만, 본 도메인은
 * single-rate per line 의 단순 모델로 시작. 복잡해지면 TaxEngine 인터페이스로 전환.</p>
 */
public final class TaxRate {

    private final String code;
    private final BigDecimal percentage;  // 10 = 10%

    private TaxRate(String code, BigDecimal percentage) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("tax code must not be blank");
        }
        if (percentage == null || percentage.signum() < 0) {
            throw new IllegalArgumentException("percentage must be non-negative");
        }
        if (percentage.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("percentage must be ≤ 100");
        }
        this.code = code;
        this.percentage = percentage;
    }

    public static TaxRate of(String code, double percentage) {
        return new TaxRate(code, BigDecimal.valueOf(percentage));
    }

    public static TaxRate of(String code, BigDecimal percentage) {
        return new TaxRate(code, percentage);
    }

    public static TaxRate exempt() {
        return new TaxRate("EXEMPT", BigDecimal.ZERO);
    }

    public static TaxRate koreaVAT() {
        return new TaxRate("KR_VAT", BigDecimal.TEN);
    }

    public String code() { return code; }
    public BigDecimal percentage() { return percentage; }

    /** 주어진 net 금액에서 세금 액수 계산 (음수 금액도 허용 — 환불 사용). */
    public Money taxOn(Money net) {
        BigDecimal factor = percentage.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        return net.multiply(factor);
    }

    public boolean isExempt() {
        return percentage.signum() == 0;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaxRate other)) return false;
        return code.equals(other.code) && percentage.compareTo(other.percentage) == 0;
    }

    @Override public int hashCode() { return Objects.hash(code, percentage); }

    @Override public String toString() {
        return code + " (" + percentage + "%)";
    }
}
