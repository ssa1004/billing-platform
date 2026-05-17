package com.example.billing.e2e

import com.example.billing.BillingApplication
import com.example.billing.adapter.out.persistence.outbox.OutboxRepository
import com.example.billing.application.command.PlaceOrderCommand
import com.example.billing.application.command.ProcessPaymentCommand
import com.example.billing.application.command.RefundCommand
import com.example.billing.application.port.`in`.PlaceOrderUseCase
import com.example.billing.application.port.`in`.ProcessPaymentUseCase
import com.example.billing.application.port.`in`.RefundUseCase
import com.example.billing.application.port.out.IdempotencyKeyStore.DuplicateRequestException
import com.example.billing.application.port.out.OrderRepository
import com.example.billing.application.port.out.RefundRepository
import com.example.billing.domain.order.OrderStatus
import com.example.billing.domain.payment.PaymentMethod
import com.example.billing.domain.refund.RefundStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.util.Currency

/**
 * E2E — Order → Payment → Refund 전 흐름을 PostgreSQL Testcontainer 위에서 검증.
 *
 * 주요 검증:
 * - Refund 가 REQUESTED → APPROVED → COMPLETED 로 진행
 * - 관련 Order 상태가 REFUNDED 로 전이
 * - Outbox 에 RefundApproved + RefundCompleted + OrderRefunded 이벤트 추가
 * - 같은 Idempotency-Key 로 두 번 환불하면 DuplicateRequestException
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = [BillingApplication::class])
@ActiveProfiles("it")
class RefundFlowIT : E2ECleanupSupport() {

    @Autowired
    lateinit var placeOrder: PlaceOrderUseCase

    @Autowired
    lateinit var processPayment: ProcessPaymentUseCase

    @Autowired
    lateinit var refundUseCase: RefundUseCase

    @Autowired
    lateinit var orders: OrderRepository

    @Autowired
    lateinit var refunds: RefundRepository

    @Autowired
    lateinit var outboxRepository: OutboxRepository

    @Test
    fun pay_then_refund_transitionsOrderAndEmitsEvents() {
        // Order → Payment
        val order = placeOrder.place(
            PlaceOrderCommand(
                "refund-it-order-1",
                "alice",
                Currency.getInstance("KRW"),
                listOf(PlaceOrderCommand.OrderLine("SKU-1", 2, BigDecimal.valueOf(1500))),
            ),
        )
        val payment = processPayment.process(
            ProcessPaymentCommand("refund-it-pay-1", order.id, PaymentMethod.CARD),
        )

        val beforeRefundOutboxSize = outboxRepository.findAll().size

        // Refund (Mock PG → 자동 승인)
        val refund = refundUseCase.refund(
            RefundCommand("refund-it-refund-1", payment.id, "customer changed mind"),
        )

        // Refund 상태 — COMPLETED, pgRefundId 채워짐
        assertThat(refund.status).isEqualTo(RefundStatus.COMPLETED)
        assertThat(refund.pgRefundId).startsWith("mock-refund-")
        assertThat(refund.amount.amount).isEqualByComparingTo("3000")

        // Order 가 REFUNDED 로 전이됐는지 영속 상태 재조회
        val reloaded = orders.findById(order.id)
            .orElseThrow { AssertionError("order should still exist") }
        assertThat(reloaded.status).isEqualTo(OrderStatus.REFUNDED)

        // Outbox 에 RefundApproved + RefundCompleted + OrderRefunded 3건 추가
        val all = outboxRepository.findAll()
        val newEvents = all.subList(beforeRefundOutboxSize, all.size)
        assertThat(newEvents).extracting<String> { it.eventType }
            .containsExactlyInAnyOrder("RefundApproved", "RefundCompleted", "OrderRefunded")
        assertThat(newEvents).allSatisfy { assertThat(it.publishedAt).isNull() }
    }

    @Test
    fun duplicate_refundIdempotencyKey_throws() {
        val order = placeOrder.place(
            PlaceOrderCommand(
                "refund-it-order-2",
                "bob",
                Currency.getInstance("KRW"),
                listOf(PlaceOrderCommand.OrderLine("SKU-2", 1, BigDecimal.valueOf(500))),
            ),
        )
        val payment = processPayment.process(
            ProcessPaymentCommand("refund-it-pay-2", order.id, PaymentMethod.CARD),
        )

        val cmd = RefundCommand("refund-it-dup-key", payment.id, "first try")
        refundUseCase.refund(cmd)

        assertThatThrownBy { refundUseCase.refund(cmd) }
            .isInstanceOf(DuplicateRequestException::class.java)
    }

    @Test
    fun refund_persistsRefundAggregateRetrievableById() {
        val order = placeOrder.place(
            PlaceOrderCommand(
                "refund-it-order-3",
                "carol",
                Currency.getInstance("KRW"),
                listOf(PlaceOrderCommand.OrderLine("SKU-3", 3, BigDecimal.valueOf(700))),
            ),
        )
        val payment = processPayment.process(
            ProcessPaymentCommand("refund-it-pay-3", order.id, PaymentMethod.CARD),
        )

        val refund = refundUseCase.refund(
            RefundCommand("refund-it-refund-3", payment.id, "wrong size"),
        )

        val reloaded = refunds.findById(refund.id)
            .orElseThrow { AssertionError("refund should be persisted") }
        assertThat(reloaded.status).isEqualTo(RefundStatus.COMPLETED)
        assertThat(reloaded.paymentId).isEqualTo(payment.id)
        assertThat(reloaded.amount.amount).isEqualByComparingTo("2100")
        assertThat(reloaded.completedAt).isNotNull
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }
}
