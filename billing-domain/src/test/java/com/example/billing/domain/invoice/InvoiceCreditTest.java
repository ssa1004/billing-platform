package com.example.billing.domain.invoice;

import com.example.billing.domain.metering.ResourceType;
import com.example.billing.domain.pricing.PricingSnapshot;
import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceCreditTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);
    private static final CustomerId ALICE = CustomerId.of("alice");

    private static Money won(long n) {
        return Money.of(BigDecimal.valueOf(n), KRW);
    }

    private static Invoice issuedInvoice(long total) {
        var line = new InvoiceLine(ResourceType.API_CALL, 1L, won(total), "demo");
        var snapshot = PricingSnapshot.of(UUID.randomUUID(), "demo", List.of(), CLOCK.instant());
        var inv = Invoice.draft(ALICE, BillingPeriod.of(YearMonth.of(2026, 5)),
                List.of(line), snapshot, CLOCK);
        inv.issue(CLOCK);
        return inv;
    }

    @Test
    void newInvoice_appliedCreditIsZero_amountDueEqualsTotal() {
        Invoice inv = issuedInvoice(100_000);
        assertThat(inv.appliedCredit()).isEqualTo(won(0));
        assertThat(inv.amountDue()).isEqualTo(won(100_000));
    }

    @Test
    void applyCredit_partial_reducesAmountDue() {
        Invoice inv = issuedInvoice(100_000);
        Money due = inv.applyCredit(won(30_000));
        assertThat(due).isEqualTo(won(70_000));
        assertThat(inv.appliedCredit()).isEqualTo(won(30_000));
    }

    @Test
    void applyCredit_full_zerosOutAmountDueButDoesNotAutoMarkPaid() {
        Invoice inv = issuedInvoice(100_000);
        inv.applyCredit(won(100_000));
        assertThat(inv.amountDue()).isEqualTo(won(0));
        assertThat(inv.status()).isEqualTo(InvoiceStatus.ISSUED);   // 자동 PAID 아님
    }

    @Test
    void applyCredit_overAmountDue_throws() {
        Invoice inv = issuedInvoice(100_000);
        assertThatThrownBy(() -> inv.applyCredit(won(150_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds amountDue");
    }

    @Test
    void applyCredit_currencyMismatch_throws() {
        Invoice inv = issuedInvoice(100_000);
        Money usd = Money.of(BigDecimal.valueOf(50), USD);
        assertThatThrownBy(() -> inv.applyCredit(usd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency mismatch");
    }

    @Test
    void applyCredit_zeroOrNegative_throws() {
        Invoice inv = issuedInvoice(100_000);
        assertThatThrownBy(() -> inv.applyCredit(won(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void applyCredit_onDraft_throws() {
        var line = new InvoiceLine(ResourceType.API_CALL, 1L, won(10_000), "demo");
        var snapshot = PricingSnapshot.of(UUID.randomUUID(), "demo", List.of(), CLOCK.instant());
        Invoice draft = Invoice.draft(ALICE, BillingPeriod.of(YearMonth.of(2026, 5)),
                List.of(line), snapshot, CLOCK);
        assertThatThrownBy(() -> draft.applyCredit(won(5_000)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAFT");
    }

    @Test
    void applyCredit_afterCancel_throws() {
        Invoice inv = issuedInvoice(100_000);
        inv.cancel();
        assertThatThrownBy(() -> inv.applyCredit(won(10_000)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("final state");
    }

    @Test
    void applyCredit_repeated_accumulates() {
        Invoice inv = issuedInvoice(100_000);
        inv.applyCredit(won(20_000));
        inv.applyCredit(won(30_000));
        assertThat(inv.appliedCredit()).isEqualTo(won(50_000));
        assertThat(inv.amountDue()).isEqualTo(won(50_000));
    }
}
