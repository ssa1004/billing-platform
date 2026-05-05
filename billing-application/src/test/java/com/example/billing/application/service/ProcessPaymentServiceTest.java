package com.example.billing.application.service;

import com.example.billing.application.command.ProcessPaymentCommand;
import com.example.billing.application.exception.OrderNotFoundException;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.application.port.out.IdempotencyKeyStore;
import com.example.billing.application.port.out.OrderRepository;
import com.example.billing.application.port.out.PaymentRepository;
import com.example.billing.application.port.out.PgClient;
import com.example.billing.domain.order.Order;
import com.example.billing.domain.order.OrderEvents;
import com.example.billing.domain.order.OrderItem;
import com.example.billing.domain.payment.Payment;
import com.example.billing.domain.payment.PaymentEvents;
import com.example.billing.domain.payment.PaymentMethod;
import com.example.billing.domain.payment.PaymentStatus;
import com.example.billing.domain.shared.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
class ProcessPaymentServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);
    private static final Currency KRW = Currency.getInstance("KRW");

    @Mock OrderRepository orders;
    @Mock PaymentRepository payments;
    @Mock PgClient pgClient;
    @Mock EventPublisher events;
    @Mock IdempotencyKeyStore idempotencyKeys;

    ProcessPaymentService service;

    @BeforeEach
    void setUp() {
        service = new ProcessPaymentService(orders, payments, pgClient, events, idempotencyKeys, CLOCK);
    }

    private Order anOrder() {
        return Order.place("alice",
                List.of(new OrderItem("SKU", 1, Money.of(BigDecimal.valueOf(1000), KRW))),
                CLOCK);
    }

    @Test
    void process_pgApproved_marksOrderPaidAndPublishesEvents() {
        Order order = anOrder();
        when(orders.findById(order.id())).thenReturn(Optional.of(order));
        when(pgClient.authorize(any())).thenReturn(PgClient.AuthorizeResult.approved("pg-tx-1"));

        Payment p = service.process(new ProcessPaymentCommand("k1", order.id(), PaymentMethod.CARD));

        assertThat(p.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(p.pgTransactionId()).isEqualTo("pg-tx-1");
        verify(events).publish(any(PaymentEvents.PaymentApproved.class));
        verify(events).publish(any(OrderEvents.OrderPaid.class));
    }

    @Test
    void process_pgRejected_marksOrderFailedAndPublishesEvents() {
        Order order = anOrder();
        when(orders.findById(order.id())).thenReturn(Optional.of(order));
        when(pgClient.authorize(any())).thenReturn(PgClient.AuthorizeResult.rejected("LIMIT_EXCEEDED", "limit"));

        Payment p = service.process(new ProcessPaymentCommand("k1", order.id(), PaymentMethod.CARD));

        assertThat(p.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.errorCode()).isEqualTo("LIMIT_EXCEEDED");
        verify(events).publish(any(PaymentEvents.PaymentRejected.class));
        verify(events).publish(any(OrderEvents.OrderFailed.class));
    }

    @Test
    void process_orderNotFound_throws() {
        var cmd = new ProcessPaymentCommand("k1", anOrder().id(), PaymentMethod.CARD);
        when(orders.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.process(cmd))
                .isInstanceOf(OrderNotFoundException.class);

        verify(payments, never()).save(any());
        verify(pgClient, never()).authorize(any());
    }
}
