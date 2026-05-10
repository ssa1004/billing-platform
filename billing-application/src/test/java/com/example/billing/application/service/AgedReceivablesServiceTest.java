package com.example.billing.application.service;

import com.example.billing.application.port.out.InvoiceRepository;
import com.example.billing.domain.invoice.Invoice;
import com.example.billing.domain.invoice.InvoiceLine;
import com.example.billing.domain.metering.ResourceType;
import com.example.billing.domain.pricing.PricingSnapshot;
import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Aged receivables 회귀 테스트. amountDue 기반 (credit 적용분 차감) 으로 집계되는지,
 * aging bucket (0-30 / 31-60 / 61-90 / 90+ 일) 분류가 due date 기준으로 맞는지 락다운.
 */
class AgedReceivablesServiceTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Instant NOW = Instant.parse("2026-05-10T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private InvoiceRepository invoiceRepository;
    private AgedReceivablesService service;

    @BeforeEach
    void setUp() {
        invoiceRepository = mock(InvoiceRepository.class);
        service = new AgedReceivablesService(invoiceRepository, CLOCK);
    }

    @Test
    void credit_적용된_invoice_는_amountDue_만큼만_미수로_집계된다() {
        // total=100,000 / appliedCredit=30,000 → amountDue=70,000 만 미수
        Invoice inv = issuedInvoiceWithCredit("alice", 100_000L, 30_000L, daysAgo(10));
        when(invoiceRepository.findUnpaid(any(), anyInt())).thenReturn(List.of(inv));

        var report = service.report();

        var buckets = report.byCustomer().get("alice");
        assertThat(buckets).isNotNull();
        assertThat(buckets.current()).isEqualTo(won(70_000));
        assertThat(buckets.total()).isEqualTo(won(70_000));
    }

    @Test
    void 전액_credit_으로_상계된_invoice_만_있는_customer_는_표에서_제외() {
        Invoice fullyCovered = issuedInvoiceWithCredit("ghost", 50_000L, 50_000L, daysAgo(45));
        when(invoiceRepository.findUnpaid(any(), anyInt())).thenReturn(List.of(fullyCovered));

        var report = service.report();

        // collection 화면에 노이즈 (잔액 0 인 row) 가 안 잡혀야 함
        assertThat(report.byCustomer()).doesNotContainKey("ghost");
    }

    @Test
    void due_date_기준으로_네_버킷에_분류된다() {
        Invoice b0 = issuedInvoice("c1", 1_000L, daysAgo(15));    // 0-30
        Invoice b30 = issuedInvoice("c1", 2_000L, daysAgo(45));   // 31-60
        Invoice b60 = issuedInvoice("c1", 3_000L, daysAgo(75));   // 61-90
        Invoice b90 = issuedInvoice("c1", 4_000L, daysAgo(120));  // 90+
        when(invoiceRepository.findUnpaid(any(), anyInt())).thenReturn(List.of(b0, b30, b60, b90));

        var buckets = service.report().byCustomer().get("c1");

        assertThat(buckets.current()).isEqualTo(won(1_000));
        assertThat(buckets.over30()).isEqualTo(won(2_000));
        assertThat(buckets.over60()).isEqualTo(won(3_000));
        assertThat(buckets.over90()).isEqualTo(won(4_000));
        assertThat(buckets.total()).isEqualTo(won(10_000));
    }

    @Test
    void 같은_customer_의_여러_invoice_는_같은_buckets_에_누적된다() {
        Invoice a = issuedInvoice("acme", 10_000L, daysAgo(5));
        Invoice b = issuedInvoice("acme", 20_000L, daysAgo(20));
        Invoice c = issuedInvoice("acme", 30_000L, daysAgo(50));
        when(invoiceRepository.findUnpaid(any(), anyInt())).thenReturn(List.of(a, b, c));

        var buckets = service.report().byCustomer().get("acme");

        assertThat(buckets.current()).isEqualTo(won(30_000));   // 5일 + 20일
        assertThat(buckets.over30()).isEqualTo(won(30_000));    // 50일
        assertThat(buckets.total()).isEqualTo(won(60_000));
    }

    @Test
    void 미수_invoice_가_없으면_빈_report() {
        when(invoiceRepository.findUnpaid(any(), anyInt())).thenReturn(List.of());

        var report = service.report();

        assertThat(report.byCustomer()).isEmpty();
        assertThat(report.asOf()).isEqualTo(NOW);
    }

    // ── helpers ──

    private static Money won(long n) {
        return Money.of(BigDecimal.valueOf(n), KRW);
    }

    private static Instant daysAgo(int days) {
        return NOW.minus(days, ChronoUnit.DAYS);
    }

    private static Invoice issuedInvoice(String customer, long total, Instant dueAt) {
        return issuedInvoiceWithCredit(customer, total, 0L, dueAt);
    }

    private static Invoice issuedInvoiceWithCredit(String customer, long total,
                                                   long appliedCredit, Instant dueAt) {
        UUID id = UUID.randomUUID();
        var line = new InvoiceLine(ResourceType.API_CALL, 1L, won(total), "demo");
        var snapshot = PricingSnapshot.of(UUID.randomUUID(), "demo", List.of(),
                Instant.parse("2026-01-01T00:00:00Z"));
        Instant createdAt = dueAt.minus(14, ChronoUnit.DAYS);   // due = createdAt + 14d
        Instant issuedAt = createdAt;
        return Invoice.restore(
                id,
                CustomerId.of(customer),
                BillingPeriod.of(YearMonth.of(2026, 5)),
                List.of(line),
                won(total),
                snapshot,
                createdAt,
                com.example.billing.domain.invoice.InvoiceStatus.ISSUED,
                won(appliedCredit),
                issuedAt,
                dueAt,
                null,
                0L
        );
    }
}
