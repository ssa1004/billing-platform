package com.example.billing.application.service;

import com.example.billing.application.command.ProcessPaymentCommand;
import com.example.billing.application.exception.OrderNotFoundException;
import com.example.billing.application.port.out.EventPublisher;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

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
class ProcessPaymentServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);
    private static final Currency KRW = Currency.getInstance("KRW");

    /** 단위 테스트용 no-op 트랜잭션 매니저 — TransactionTemplate 콜백을 그대로 실행. */
    private static final PlatformTransactionManager NO_OP_TX_MANAGER = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
        }
    };

    @Mock OrderRepository orders;
    @Mock PaymentRepository payments;
    @Mock PgClient pgClient;
    @Mock EventPublisher events;
    @Mock IdempotentExecution idempotency;

    ProcessPaymentService service;

    @BeforeEach
    void setUp() {
        service = new ProcessPaymentService(orders, payments, pgClient, events, idempotency,
                CLOCK, NO_OP_TX_MANAGER);
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
        // Phase 1 의 save 로 점유한 Payment 를 Phase 3 가 다시 로드하므로 동일 인스턴스 반환
        doAnswer(inv -> {
            Payment p = inv.getArgument(0);
            when(payments.findById(p.id())).thenReturn(Optional.of(p));
            return null;
        }).when(payments).save(any(Payment.class));
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
        doAnswer(inv -> {
            Payment p = inv.getArgument(0);
            when(payments.findById(p.id())).thenReturn(Optional.of(p));
            return null;
        }).when(payments).save(any(Payment.class));
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
