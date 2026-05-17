package com.example.billing.e2e

import com.example.billing.BillingApplication
import com.example.billing.adapter.out.persistence.outbox.OutboxRepository
import com.example.billing.application.command.PlaceOrderCommand
import com.example.billing.application.command.ProcessPaymentCommand
import com.example.billing.application.port.`in`.PlaceOrderUseCase
import com.example.billing.application.port.`in`.ProcessPaymentUseCase
import com.example.billing.application.port.out.IdempotencyKeyStore.DuplicateRequestException
import com.example.billing.domain.order.OrderStatus
import com.example.billing.domain.payment.PaymentMethod
import com.example.billing.domain.payment.PaymentStatus
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
 * E2E — 실제 PostgreSQL 컨테이너에서 Order → Payment → Outbox 흐름 검증.
 *
 * 검증 포인트:
 * - Flyway V1 마이그레이션 적용
 * - JPA mapper 의 entity ↔ domain round-trip
 * - Outbox 테이블에 트랜잭션 안에서 INSERT 됨
 * - MockPgClient 사용 (Kafka relay 는 disabled)
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = [BillingApplication::class])
@ActiveProfiles("it")
class OrderPaymentFlowIT : E2ECleanupSupport() {

    @Autowired
    lateinit var placeOrder: PlaceOrderUseCase

    @Autowired
    lateinit var processPayment: ProcessPaymentUseCase

    @Autowired
    lateinit var outboxRepository: OutboxRepository

    @Test
    fun place_then_pay_writesOutboxEvents() {
        // 1. Order 생성
        val order = placeOrder.place(
            PlaceOrderCommand(
                "key-1",
                "alice",
                Currency.getInstance("KRW"),
                listOf(PlaceOrderCommand.OrderLine("SKU-1", 2, BigDecimal.valueOf(1000))),
            ),
        )

        assertThat(order.status).isEqualTo(OrderStatus.CREATED)
        assertThat(order.totalAmount.amount).isEqualByComparingTo("2000")

        // Outbox 에 OrderPlaced 이벤트 1건
        val afterPlace = outboxRepository.findAll()
        assertThat(afterPlace).extracting<String> { it.eventType }.contains("OrderPlaced")

        // 2. 결제 (Mock PG → 자동 승인)
        val payment = processPayment.process(
            ProcessPaymentCommand("pay-key-1", order.id, PaymentMethod.CARD),
        )

        assertThat(payment.status).isEqualTo(PaymentStatus.APPROVED)
        assertThat(payment.pgTransactionId).startsWith("mock-pg-")

        // Outbox 에 PaymentApproved + OrderPaid 추가됨
        val afterPay = outboxRepository.findAll()
        assertThat(afterPay).extracting<String> { it.eventType }
            .contains("OrderPlaced", "PaymentApproved", "OrderPaid")
        assertThat(afterPay).hasSize(3)

        // 모두 unpublished 상태 (relay disabled)
        assertThat(afterPay).allSatisfy { assertThat(it.publishedAt).isNull() }
    }

    @Test
    fun duplicate_idempotencyKey_throws() {
        val cmd = PlaceOrderCommand(
            "dup-key",
            "alice",
            Currency.getInstance("KRW"),
            listOf(PlaceOrderCommand.OrderLine("SKU-1", 1, BigDecimal.valueOf(1000))),
        )
        placeOrder.place(cmd)

        // 같은 키로 두 번째 호출 → DuplicateRequestException
        assertThatThrownBy { placeOrder.place(cmd) }
            .isInstanceOf(DuplicateRequestException::class.java)
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }
}
