package com.example.billing.domain.invoice;

import com.example.billing.domain.shared.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxRateTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void 한국_부가세_10퍼센트() {
        TaxRate vat = TaxRate.koreaVAT();
        Money net = Money.of(BigDecimal.valueOf(100_000), KRW);

        Money tax = vat.taxOn(net);

        assertThat(tax.amount()).isEqualByComparingTo("10000");
    }

    @Test
    void 면세() {
        TaxRate exempt = TaxRate.exempt();
        Money net = Money.of(BigDecimal.valueOf(50_000), KRW);

        assertThat(exempt.taxOn(net).amount()).isEqualByComparingTo("0");
        assertThat(exempt.isExempt()).isTrue();
    }

    @Test
    void USD_금액에_세율_적용시_currency_유지() {
        TaxRate rate = TaxRate.of("US_SALES_TAX", 8.875);
        Money net = Money.of(BigDecimal.valueOf(100), USD);

        Money tax = rate.taxOn(net);

        assertThat(tax.currency()).isEqualTo(USD);
        assertThat(tax.amount()).isEqualByComparingTo("8.88");
    }

    @Test
    void 음수_퍼센티지_금지() {
        assertThatThrownBy(() -> TaxRate.of("WEIRD", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 백퍼센트_초과_금지() {
        assertThatThrownBy(() -> TaxRate.of("CRAZY", 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 환불_금액_음수_에도_세금_계산_가능() {
        TaxRate vat = TaxRate.koreaVAT();
        Money refund = Money.of(BigDecimal.valueOf(-30_000), KRW);

        Money tax = vat.taxOn(refund);

        assertThat(tax.amount()).isEqualByComparingTo("-3000");
    }
}
