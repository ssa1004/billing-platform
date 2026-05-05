package com.example.billing.e2e;

import com.example.billing.WalletApplication;
import com.example.billing.adapter.out.persistence.outbox.OutboxRepository;
import com.example.billing.application.command.PlaceOrderCommand;
import com.example.billing.application.command.ProcessPaymentCommand;
import com.example.billing.application.command.RefundCommand;
import com.example.billing.application.port.in.PlaceOrderUseCase;
import com.example.billing.application.port.out.IdempotencyKeyStore.DuplicateRequestException;
import com.example.billing.application.port.in.ProcessPaymentUseCase;
import com.example.billing.application.port.in.RefundUseCase;
import com.example.billing.application.port.out.OrderRepository;
import com.example.billing.application.port.out.RefundRepository;
import com.example.billing.domain.order.Order;
import com.example.billing.domain.order.OrderStatus;
import com.example.billing.domain.payment.Payment;
import com.example.billing.domain.payment.PaymentMethod;
import com.example.billing.domain.refund.Refund;
import com.example.billing.domain.refund.RefundStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E2E — Order → Payment → Refund 전 흐름을 PostgreSQL Testcontainer 위에서 검증.
 *
 * <p>주요 검증:</p>
 * <ul>
 *   <li>Refund 가 REQUESTED → APPROVED → COMPLETED 로 진행</li>
 *   <li>관련 Order 상태가 REFUNDED 로 전이</li>
 *   <li>Outbox 에 RefundApproved + RefundCompleted + OrderRefunded 이벤트 추가</li>
 *   <li>같은 Idempotency-Key 로 두 번 환불하면 DuplicateRequestException</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = WalletApplication.class)
@ActiveProfiles("it")
class RefundFlowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired PlaceOrderUseCase placeOrder;
    @Autowired ProcessPaymentUseCase processPayment;
    @Autowired RefundUseCase refundUseCase;
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired OutboxRepository outboxRepository;

    @Test
    void pay_then_refund_transitionsOrderAndEmitsEvents() {
        // Order → Payment
        Order order = placeOrder.place(new PlaceOrderCommand(
                "refund-it-order-1",
                "alice",
                Currency.getInstance("KRW"),
                List.of(new PlaceOrderCommand.OrderLine("SKU-1", 2, BigDecimal.valueOf(1500)))
        ));
        Payment payment = processPayment.process(new ProcessPaymentCommand(
                "refund-it-pay-1", order.id(), PaymentMethod.CARD));

        int beforeRefundOutboxSize = outboxRepository.findAll().size();

        // Refund (Mock PG → 자동 승인)
        Refund refund = refundUseCase.refund(new RefundCommand(
                "refund-it-refund-1", payment.id(), "customer changed mind"));

        // Refund 상태 — COMPLETED, pgRefundId 채워짐
        assertThat(refund.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(refund.pgRefundId()).startsWith("mock-refund-");
        assertThat(refund.amount().amount()).isEqualByComparingTo("3000");

        // Order 가 REFUNDED 로 전이됐는지 영속 상태 재조회
        Order reloaded = orders.findById(order.id())
                .orElseThrow(() -> new AssertionError("order should still exist"));
        assertThat(reloaded.status()).isEqualTo(OrderStatus.REFUNDED);

        // Outbox 에 RefundApproved + RefundCompleted + OrderRefunded 3건 추가
        var newEvents = outboxRepository.findAll().subList(beforeRefundOutboxSize,
                outboxRepository.findAll().size());
        assertThat(newEvents).extracting(o -> o.getEventType())
                .containsExactlyInAnyOrder("RefundApproved", "RefundCompleted", "OrderRefunded");
        assertThat(newEvents).allSatisfy(o -> assertThat(o.getPublishedAt()).isNull());
    }

    @Test
    void duplicate_refundIdempotencyKey_throws() {
        Order order = placeOrder.place(new PlaceOrderCommand(
                "refund-it-order-2",
                "bob",
                Currency.getInstance("KRW"),
                List.of(new PlaceOrderCommand.OrderLine("SKU-2", 1, BigDecimal.valueOf(500)))
        ));
        Payment payment = processPayment.process(new ProcessPaymentCommand(
                "refund-it-pay-2", order.id(), PaymentMethod.CARD));

        var cmd = new RefundCommand("refund-it-dup-key", payment.id(), "first try");
        refundUseCase.refund(cmd);

        assertThatThrownBy(() -> refundUseCase.refund(cmd))
                .isInstanceOf(DuplicateRequestException.class);
    }

    @Test
    void refund_persistsRefundAggregateRetrievableById() {
        Order order = placeOrder.place(new PlaceOrderCommand(
                "refund-it-order-3",
                "carol",
                Currency.getInstance("KRW"),
                List.of(new PlaceOrderCommand.OrderLine("SKU-3", 3, BigDecimal.valueOf(700)))
        ));
        Payment payment = processPayment.process(new ProcessPaymentCommand(
                "refund-it-pay-3", order.id(), PaymentMethod.CARD));

        Refund refund = refundUseCase.refund(new RefundCommand(
                "refund-it-refund-3", payment.id(), "wrong size"));

        Refund reloaded = refunds.findById(refund.id())
                .orElseThrow(() -> new AssertionError("refund should be persisted"));
        assertThat(reloaded.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(reloaded.paymentId()).isEqualTo(payment.id());
        assertThat(reloaded.amount().amount()).isEqualByComparingTo("2100");
        assertThat(reloaded.completedAt()).isNotNull();
    }
}
