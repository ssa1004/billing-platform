package com.example.billing.application.service;

import com.example.billing.application.port.in.AuditLogger;
import com.example.billing.application.port.out.InvoiceRepository;
import com.example.billing.application.port.out.PaymentRepository;
import com.example.billing.application.port.out.RefundRepository;
import com.example.billing.domain.audit.AuditAction;
import com.example.billing.domain.audit.AuditActor;
import com.example.billing.domain.invoice.Invoice;
import com.example.billing.domain.invoice.InvoiceLine;
import com.example.billing.domain.invoice.InvoiceStatus;
import com.example.billing.domain.metering.ResourceType;
import com.example.billing.domain.order.OrderId;
import com.example.billing.domain.payment.Payment;
import com.example.billing.domain.payment.PaymentId;
import com.example.billing.domain.payment.PaymentMethod;
import com.example.billing.domain.payment.PaymentStatus;
import com.example.billing.domain.pricing.PricingSnapshot;
import com.example.billing.domain.pricing.Tier;
import com.example.billing.domain.refund.Refund;
import com.example.billing.domain.refund.RefundId;
import com.example.billing.domain.refund.RefundStatus;
import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SoftDeleteServiceTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Instant NOW = Instant.parse("2026-05-09T10:00:00Z");

    @Mock InvoiceRepository invoices;
    @Mock PaymentRepository payments;
    @Mock RefundRepository refunds;
    @Mock AuditLogger auditLogger;

    @InjectMocks SoftDeleteService service;

    private AuditActor operator;

    @BeforeEach
    void setUp() {
        operator = AuditActor.operator("op-alice", "10.0.0.1", "Chrome/123");
    }

    // ── invoice ──

    @Test
    void softDeleteInvoice_marksRowAndEmitsAudit() {
        UUID id = UUID.randomUUID();
        Invoice invoice = anInvoice(id);
        when(invoices.findById(id)).thenReturn(Optional.of(invoice));
        when(invoices.softDelete(id, "op-alice")).thenReturn(true);

        boolean ok = service.softDeleteInvoice(id, operator, "duplicate row");

        assertThat(ok).isTrue();
        verify(invoices).softDelete(id, "op-alice");
        verify(auditLogger).log(eq(operator), eq(AuditAction.SOFT_DELETED), eq("Invoice"),
                eq(id.toString()), anyString(), eq(null), eq("duplicate row"));
    }

    @Test
    void softDeleteInvoice_alreadyDeleted_skipsAudit() {
        UUID id = UUID.randomUUID();
        when(invoices.findById(id)).thenReturn(Optional.empty());

        boolean ok = service.softDeleteInvoice(id, operator, "any");

        assertThat(ok).isFalse();
        verify(invoices, never()).softDelete(any(), anyString());
        verifyNoInteractions(auditLogger);
    }

    @Test
    void softDeleteInvoice_concurrentDelete_returnsFalseWithoutAudit() {
        // findById 는 활성 row 를 봤지만 UPDATE 직전에 다른 트랜잭션이 먼저 mark — 0행 영향.
        UUID id = UUID.randomUUID();
        when(invoices.findById(id)).thenReturn(Optional.of(anInvoice(id)));
        when(invoices.softDelete(id, "op-alice")).thenReturn(false);

        boolean ok = service.softDeleteInvoice(id, operator, "race");

        assertThat(ok).isFalse();
        verifyNoInteractions(auditLogger);
    }

    // ── payment ──

    @Test
    void softDeletePayment_marksRowAndEmitsAudit() {
        PaymentId id = PaymentId.newId();
        Payment payment = aPayment(id);
        when(payments.findById(id)).thenReturn(Optional.of(payment));
        when(payments.softDelete(id, "op-alice")).thenReturn(true);

        boolean ok = service.softDeletePayment(id, operator, "test cleanup");

        assertThat(ok).isTrue();
        verify(payments).softDelete(id, "op-alice");
        verify(auditLogger).log(eq(operator), eq(AuditAction.SOFT_DELETED), eq("Payment"),
                eq(id.value().toString()), anyString(), eq(null), eq("test cleanup"));
    }

    // ── refund ──

    @Test
    void softDeleteRefund_marksRowAndEmitsAudit() {
        RefundId id = RefundId.newId();
        Refund refund = aRefund(id);
        when(refunds.findById(id)).thenReturn(Optional.of(refund));
        when(refunds.softDelete(id, "op-alice")).thenReturn(true);

        boolean ok = service.softDeleteRefund(id, operator, "duplicate");

        assertThat(ok).isTrue();
        verify(refunds).softDelete(id, "op-alice");
        verify(auditLogger, times(1)).log(eq(operator), eq(AuditAction.SOFT_DELETED), eq("Refund"),
                eq(id.value().toString()), anyString(), eq(null), eq("duplicate"));
    }

    @Test
    void softDeleteRefund_repository_returns_false_skips_audit() {
        RefundId id = RefundId.newId();
        when(refunds.findById(id)).thenReturn(Optional.of(aRefund(id)));
        when(refunds.softDelete(id, "op-alice")).thenReturn(false);

        assertThat(service.softDeleteRefund(id, operator, "any")).isFalse();
        verifyNoInteractions(auditLogger);
    }

    // ── builders ──

    private Invoice anInvoice(UUID id) {
        Money line = Money.of(new BigDecimal("1000"), KRW);
        InvoiceLine invoiceLine = new InvoiceLine(ResourceType.API_CALL, 1L, line, "1 call");
        PricingSnapshot snapshot = PricingSnapshot.of(UUID.randomUUID(), "p1",
                List.of(new Tier(ResourceType.API_CALL, null, line)), NOW);
        return Invoice.restore(id, CustomerId.of("c-1"),
                BillingPeriod.of(YearMonth.of(2026, 5)),
                List.of(invoiceLine), line, snapshot, NOW,
                InvoiceStatus.ISSUED, Money.zero(KRW), NOW, NOW.plusSeconds(60), null, 0L);
    }

    private Payment aPayment(PaymentId id) {
        return Payment.restore(id, new OrderId(UUID.randomUUID()),
                Money.of(new BigDecimal("1000"), KRW), PaymentMethod.CARD,
                "ik-1", PaymentStatus.APPROVED, "pg-tx-1", null, null,
                NOW, NOW, 0L);
    }

    private Refund aRefund(RefundId id) {
        return Refund.restore(id, PaymentId.newId(),
                Money.of(new BigDecimal("500"), KRW), "duplicate", "ik-r-1",
                RefundStatus.COMPLETED, "pg-rf-1", NOW, NOW.plusSeconds(10), 0L);
    }
}
