package com.example.billing.application.service;

import com.example.billing.application.command.RefundCommand;
import com.example.billing.application.exception.OrderNotFoundException;
import com.example.billing.application.exception.PaymentNotFoundException;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.application.port.out.IdempotencyKeyStore;
import com.example.billing.application.port.out.OrderRepository;
import com.example.billing.application.port.out.PaymentRepository;
import com.example.billing.application.port.out.PgClient;
import com.example.billing.application.port.out.RefundRepository;
import com.example.billing.domain.order.Order;
import com.example.billing.domain.order.OrderItem;
import com.example.billing.domain.payment.Payment;
import com.example.billing.domain.payment.PaymentMethod;
import com.example.billing.domain.refund.Refund;
import com.example.billing.domain.refund.RefundStatus;
import com.example.billing.domain.shared.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundServiceTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    @Mock PaymentRepository payments;
    @Mock RefundRepository refunds;
    @Mock OrderRepository orders;
    @Mock PgClient pgClient;
    @Mock EventPublisher events;
    @Mock IdempotencyKeyStore idempotencyKeys;

    RefundService service;

    @BeforeEach
    void setUp() {
        service = new RefundService(payments, refunds, orders, pgClient, events, idempotencyKeys, CLOCK);
    }

    private Order paidOrder() {
        Order o = Order.place("alice",
                List.of(new OrderItem("SKU", 1, Money.of(BigDecimal.valueOf(1000), KRW))), CLOCK);
        o.markPaid("payment-1", CLOCK);
        return o;
    }

    @Test
    void refund_pgApproved_completesAndPublishesEvents() {
        Order order = paidOrder();
        Payment payment = Payment.initiate(order.id(), order.totalAmount(), PaymentMethod.CARD, "k", CLOCK);
        payment.approve("pg-tx-1", CLOCK);

        when(payments.findById(payment.id())).thenReturn(Optional.of(payment));
        when(orders.findById(order.id())).thenReturn(Optional.of(order));
        when(pgClient.refund(any())).thenReturn(PgClient.RefundResult.approved("pg-refund-1"));

        Refund r = service.refund(new RefundCommand("k1", payment.id(), "user request"));

        assertThat(r.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(r.pgRefundId()).isEqualTo("pg-refund-1");
        verify(events, atLeast(2)).publish(any());   // RefundApproved + RefundCompleted + OrderRefunded
    }

    @Test
    void refund_pgRejected_failsAndPublishesFailedEvent() {
        Order order = paidOrder();
        Payment payment = Payment.initiate(order.id(), order.totalAmount(), PaymentMethod.CARD, "k", CLOCK);
        payment.approve("pg-tx-1", CLOCK);

        when(payments.findById(payment.id())).thenReturn(Optional.of(payment));
        when(pgClient.refund(any())).thenReturn(PgClient.RefundResult.rejected("PG offline"));

        Refund r = service.refund(new RefundCommand("k1", payment.id(), "user"));

        assertThat(r.status()).isEqualTo(RefundStatus.FAILED);
        verify(events).publish(any(Refund.RefundFailed.class));
    }

    @Test
    void refund_paymentNotFound_throws() {
        when(payments.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.refund(new RefundCommand("k1", com.example.billing.domain.payment.PaymentId.newId(), "x")))
                .isInstanceOf(PaymentNotFoundException.class);

        verifyNoInteractions(pgClient);
    }
}
