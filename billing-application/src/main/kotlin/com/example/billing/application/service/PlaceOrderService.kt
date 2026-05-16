package com.example.billing.application.service

import com.example.billing.application.command.PlaceOrderCommand
import com.example.billing.application.port.`in`.PlaceOrderUseCase
import com.example.billing.application.port.out.EventPublisher
import com.example.billing.application.port.out.OrderRepository
import com.example.billing.domain.order.Order
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 주문 생성 use case.
 *
 * 흐름:
 *  1. Idempotency-Key 획득 (Redis NX) — 중복 시 `DuplicateRequestException`
 *  2. [Order.place] — 도메인 invariant (가격 합산, 통화 정합)
 *  3. OrderRepository.save
 *  4. OrderPlaced 이벤트를 Outbox 에 INSERT (같은 트랜잭션) — Kafka publish 는 Relay 가 별도
 */
@Service
open class PlaceOrderService(
    private val orders: OrderRepository,
    private val idempotency: IdempotentExecution,
    private val events: EventPublisher,
    private val clock: Clock,
) : PlaceOrderUseCase {

    @Transactional
    override fun place(command: PlaceOrderCommand): Order {
        idempotency.acquireAndReleaseOnRollback(command.idempotencyKey)

        val order = Order.place(command.buyerId, command.toOrderItems(), clock)
        orders.save(order)

        events.publish(order.toPlacedEvent(clock))
        log.info(
            "order placed id={} buyer={} total={}",
            order.id, order.buyerId, order.totalAmount,
        )
        return order
    }

    companion object {
        private val log = LoggerFactory.getLogger(PlaceOrderService::class.java)
    }
}
