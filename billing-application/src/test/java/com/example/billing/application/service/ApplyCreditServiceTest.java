package com.example.billing.application.service;

import com.example.billing.application.command.ApplyCreditCommand;
import com.example.billing.application.exception.InvoiceNotFoundException;
import com.example.billing.application.port.in.AuditLogger;
import com.example.billing.application.port.out.CreditRepository;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.application.port.out.InvoiceRepository;
import com.example.billing.domain.credit.Credit;
import com.example.billing.domain.credit.CreditEvents;
import com.example.billing.domain.credit.CreditType;
import com.example.billing.domain.invoice.Invoice;
import com.example.billing.domain.invoice.InvoiceLine;
import com.example.billing.domain.metering.ResourceType;
import com.example.billing.domain.pricing.PricingSnapshot;
import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplyCreditServiceTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Instant NOW = Instant.parse("2026-05-04T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final CustomerId ALICE = CustomerId.of("alice");

    @Mock CreditRepository credits;
    @Mock InvoiceRepository invoices;
    @Mock EventPublisher events;
    @Mock IdempotentExecution idempotency;
    @Mock AuditLogger audit;

    ApplyCreditService service;

    @BeforeEach
    void setUp() {
        service = new ApplyCreditService(credits, invoices, events, idempotency, audit, CLOCK);
    }

    private static Money won(long n) {
        return Money.of(BigDecimal.valueOf(n), KRW);
    }

    private static Invoice issuedInvoice(long total) {
        var line = new InvoiceLine(ResourceType.API_CALL, 1L, won(total), "demo");
        var snapshot = PricingSnapshot.of(UUID.randomUUID(), "demo", List.of(), NOW);
        var inv = Invoice.draft(ALICE, BillingPeriod.of(YearMonth.of(2026, 5)),
                List.of(line), snapshot, CLOCK);
        inv.issue(CLOCK);
        return inv;
    }

    private static Credit promo(long amount, Instant validUntil) {
        return Credit.grant(ALICE, CreditType.PROMO, won(amount),
                NOW.minusSeconds(60), validUntil, "test", CLOCK);
    }

    @Test
    void invoiceNotFound_throws() {
        var inv = issuedInvoice(50_000);
        when(invoices.findById(inv.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.apply(new ApplyCreditCommand("k", "alice", inv.id(), won(10_000))))
                .isInstanceOf(InvoiceNotFoundException.class);
    }

    @Test
    void singleCredit_partial_appliesAndUpdatesInvoice() {
        var inv = issuedInvoice(100_000);
        var credit = promo(50_000, NOW.plusSeconds(86400));
        when(invoices.findById(inv.id())).thenReturn(Optional.of(inv));
        when(credits.findUsable(ALICE, NOW)).thenReturn(List.of(credit));

        Money applied = service.apply(new ApplyCreditCommand("k", "alice", inv.id(), won(30_000)));

        assertThat(applied).isEqualTo(won(30_000));
        assertThat(credit.balance()).isEqualTo(won(20_000));
        assertThat(inv.appliedCredit()).isEqualTo(won(30_000));
        assertThat(inv.amountDue()).isEqualTo(won(70_000));
        verify(invoices).save(inv);
        verify(credits).save(credit);
    }

    @Test
    void multipleCredits_appliedInRepoOrder_untilCapReached() {
        var inv = issuedInvoice(100_000);
        // findUsable 정렬은 repo 책임 — 여기선 주어진 순서대로 사용한다고만 검증
        var c1 = promo(30_000, NOW.plusSeconds(60));   // 임박
        var c2 = promo(80_000, NOW.plusSeconds(86400));// 여유
        when(invoices.findById(inv.id())).thenReturn(Optional.of(inv));
        when(credits.findUsable(ALICE, NOW)).thenReturn(List.of(c1, c2));

        Money applied = service.apply(new ApplyCreditCommand("k", "alice", inv.id(), won(50_000)));

        assertThat(applied).isEqualTo(won(50_000));
        assertThat(c1.balance()).isEqualTo(won(0));     // 30_000 차감 (전액)
        assertThat(c2.balance()).isEqualTo(won(60_000));// 20_000 차감
        assertThat(inv.appliedCredit()).isEqualTo(won(50_000));
    }

    @Test
    void capLimitedByInvoiceAmountDue() {
        var inv = issuedInvoice(20_000);   // 작은 invoice
        var c = promo(100_000, NOW.plusSeconds(86400));
        when(invoices.findById(inv.id())).thenReturn(Optional.of(inv));
        when(credits.findUsable(ALICE, NOW)).thenReturn(List.of(c));

        // 사용자가 50_000 까지 적용 요청해도 invoice 가 20_000 라 그 이상은 못 차감
        Money applied = service.apply(new ApplyCreditCommand("k", "alice", inv.id(), won(50_000)));

        assertThat(applied).isEqualTo(won(20_000));
        assertThat(c.balance()).isEqualTo(won(80_000));
        assertThat(inv.amountDue()).isEqualTo(won(0));
    }

    @Test
    void differentCurrencyCredit_isSkipped() {
        var inv = issuedInvoice(100_000);
        var usdCredit = Credit.grant(ALICE, CreditType.PROMO,
                Money.of(BigDecimal.valueOf(100), USD),
                NOW.minusSeconds(60), NOW.plusSeconds(86400), "usd", CLOCK);
        when(invoices.findById(inv.id())).thenReturn(Optional.of(inv));
        when(credits.findUsable(ALICE, NOW)).thenReturn(List.of(usdCredit));

        Money applied = service.apply(new ApplyCreditCommand("k", "alice", inv.id(), won(50_000)));

        assertThat(applied).isEqualTo(won(0));
        assertThat(usdCredit.balance().amount()).isEqualByComparingTo("100");  // 손대지 않음
        // invoice 도 변경 X (applied == 0)
        verify(invoices, never()).save(any());
    }

    @Test
    void noUsableCredits_returnsZero_noInvoiceSave() {
        var inv = issuedInvoice(100_000);
        when(invoices.findById(inv.id())).thenReturn(Optional.of(inv));
        when(credits.findUsable(ALICE, NOW)).thenReturn(List.of());

        Money applied = service.apply(new ApplyCreditCommand("k", "alice", inv.id(), won(50_000)));

        assertThat(applied).isEqualTo(won(0));
        verify(invoices, never()).save(any());
    }

    @Test
    void publishesCreditConsumedEventPerCreditUsed() {
        var inv = issuedInvoice(100_000);
        var c1 = promo(30_000, NOW.plusSeconds(60));
        var c2 = promo(80_000, NOW.plusSeconds(86400));
        when(invoices.findById(inv.id())).thenReturn(Optional.of(inv));
        when(credits.findUsable(ALICE, NOW)).thenReturn(List.of(c1, c2));

        service.apply(new ApplyCreditCommand("k", "alice", inv.id(), won(50_000)));

        ArgumentCaptor<CreditEvents.CreditConsumed> captor =
                ArgumentCaptor.forClass(CreditEvents.CreditConsumed.class);
        verify(events, times(2)).publish(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(CreditEvents.CreditConsumed::consumedAmount)
                .containsExactly(won(30_000), won(20_000));
    }

    @Test
    void zeroOrNegativeApplyAtMost_returnsZero() {
        Money applied = service.apply(new ApplyCreditCommand("k", "alice", UUID.randomUUID(), won(0)));
        assertThat(applied).isEqualTo(won(0));
        verify(invoices, never()).findById(any());
    }
}
