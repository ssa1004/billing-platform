package com.example.billing.application.service;

import com.example.billing.application.command.RunSettlementCommand;
import com.example.billing.application.command.SettlementResult;
import com.example.billing.application.port.out.AdvisoryLock;
import com.example.billing.application.port.out.AggregatedUsageRepository;
import com.example.billing.application.port.out.InvoiceRepository;
import com.example.billing.application.port.out.PaymentClient;
import com.example.billing.application.port.out.PricingPlanRepository;
import com.example.billing.domain.invoice.Invoice;
import com.example.billing.domain.invoice.InvoiceStatus;
import com.example.billing.domain.metering.AggregatedUsage;
import com.example.billing.domain.metering.ResourceType;
import com.example.billing.domain.pricing.PricingPlan;
import com.example.billing.domain.pricing.Tier;
import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunSettlementServiceTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-06-01T01:00:00Z"), ZoneOffset.UTC);

    private AdvisoryLock advisoryLock;
    private AggregatedUsageRepository usageRepo;
    private PricingPlanRepository pricingRepo;
    private InvoiceRepository invoiceRepo;
    private PaymentClient paymentClient;
    private RunSettlementService service;

    private final CustomerId customer = CustomerId.of("acme-corp");
    private final BillingPeriod period = BillingPeriod.of(2026, 5);

    @BeforeEach
    void setUp() {
        advisoryLock = mock(AdvisoryLock.class);
        usageRepo = mock(AggregatedUsageRepository.class);
        pricingRepo = mock(PricingPlanRepository.class);
        invoiceRepo = mock(InvoiceRepository.class);
        paymentClient = mock(PaymentClient.class);
        service = new RunSettlementService(advisoryLock, usageRepo, pricingRepo, invoiceRepo,
                paymentClient, FIXED);
    }

    @Test
    void advisory_lock_을_고객_기간_키로_획득한다() {
        when(usageRepo.findByCustomerAndPeriod(customer, period)).thenReturn(List.of());
        when(invoiceRepo.findBy(customer, period)).thenReturn(Optional.empty());

        service.run(new RunSettlementCommand(customer, period));

        verify(advisoryLock, times(1)).lock("settlement:acme-corp:2026-05");
    }

    @Test
    void 이미_invoice_가_있으면_skip() {
        Invoice existing = mock(Invoice.class);
        when(existing.id()).thenReturn(java.util.UUID.randomUUID());
        when(invoiceRepo.findBy(customer, period)).thenReturn(Optional.of(existing));

        SettlementResult result = service.run(new RunSettlementCommand(customer, period));

        assertThat(result.alreadyProcessed()).isTrue();
        verify(paymentClient, never()).charge(any());
    }

    @Test
    void 사용량_0_이면_invoice_생성_안_함() {
        when(invoiceRepo.findBy(customer, period)).thenReturn(Optional.empty());
        when(usageRepo.findByCustomerAndPeriod(customer, period)).thenReturn(List.of());

        SettlementResult result = service.run(new RunSettlementCommand(customer, period));

        assertThat(result.alreadyProcessed()).isTrue();
        verify(invoiceRepo, never()).save(any());
        verify(paymentClient, never()).charge(any());
    }

    @Test
    void 사용량_있으면_invoice_생성_및_결제_시도() {
        when(invoiceRepo.findBy(customer, period)).thenReturn(Optional.empty());
        when(usageRepo.findByCustomerAndPeriod(customer, period)).thenReturn(List.of(
                AggregatedUsage.of(customer, ResourceType.API_CALL, period,
                        20000L, 20L, FIXED.instant())
        ));
        PricingPlan plan = PricingPlan.create("Standard", List.of(
                new Tier(ResourceType.API_CALL, 10000L, Money.of(BigDecimal.ZERO, KRW)),
                new Tier(ResourceType.API_CALL, null, Money.of(BigDecimal.valueOf(1L), KRW))
        ), Instant.parse("2026-01-01T00:00:00Z"));
        when(pricingRepo.findEffective(customer, period.toExclusive())).thenReturn(Optional.of(plan));
        when(paymentClient.charge(any())).thenReturn(true);

        SettlementResult result = service.run(new RunSettlementCommand(customer, period));

        assertThat(result.alreadyProcessed()).isFalse();
        assertThat(result.paymentSucceeded()).isTrue();

        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepo, times(2)).save(captor.capture());  // ISSUED 후 markPaid 후 한 번 더
        Invoice saved = captor.getAllValues().get(0);
        assertThat(saved.lines()).hasSize(1);
        assertThat(saved.total().amount()).isEqualByComparingTo("10000");  // 10000 초과분 × 1원
    }

    @Test
    void 결제_실패해도_invoice_는_ISSUED_로_저장된다() {
        when(invoiceRepo.findBy(customer, period)).thenReturn(Optional.empty());
        when(usageRepo.findByCustomerAndPeriod(customer, period)).thenReturn(List.of(
                AggregatedUsage.of(customer, ResourceType.API_CALL, period,
                        5000L, 5L, FIXED.instant())
        ));
        PricingPlan plan = PricingPlan.create("Standard", List.of(
                new Tier(ResourceType.API_CALL, null, Money.of(BigDecimal.valueOf(2L), KRW))
        ), Instant.parse("2026-01-01T00:00:00Z"));
        when(pricingRepo.findEffective(customer, period.toExclusive())).thenReturn(Optional.of(plan));
        when(paymentClient.charge(any())).thenReturn(false);

        SettlementResult result = service.run(new RunSettlementCommand(customer, period));

        assertThat(result.paymentSucceeded()).isFalse();
        // invoice 는 한 번 (ISSUED 로) 저장되고, markPaid 는 호출 안 됨
        verify(invoiceRepo, times(1)).save(any());
    }

    @Test
    void 결제_치명적_실패는_invoice_상태에_영향_없음() {
        when(invoiceRepo.findBy(customer, period)).thenReturn(Optional.empty());
        when(usageRepo.findByCustomerAndPeriod(customer, period)).thenReturn(List.of(
                AggregatedUsage.of(customer, ResourceType.API_CALL, period,
                        100L, 1L, FIXED.instant())
        ));
        PricingPlan plan = PricingPlan.create("Standard", List.of(
                new Tier(ResourceType.API_CALL, null, Money.of(BigDecimal.valueOf(1L), KRW))
        ), Instant.parse("2026-01-01T00:00:00Z"));
        when(pricingRepo.findEffective(customer, period.toExclusive())).thenReturn(Optional.of(plan));
        when(paymentClient.charge(any())).thenThrow(
                new PaymentClient.PaymentFatalException("card expired"));

        SettlementResult result = service.run(new RunSettlementCommand(customer, period));

        assertThat(result.paymentSucceeded()).isFalse();
        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepo, times(1)).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(InvoiceStatus.ISSUED);
    }
}
