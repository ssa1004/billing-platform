package com.example.billing.application.service;

import com.example.billing.application.command.PlaceOrderCommand;
import com.example.billing.application.port.in.PlaceOrderUseCase;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.application.port.out.IdempotencyKeyStore;
import com.example.billing.application.port.out.OrderRepository;
import com.example.billing.domain.order.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 주문 생성 use case.
 *
 * <p>흐름:</p>
 * <ol>
 *   <li>Idempotency-Key 획득 (Redis NX) — 중복 시 {@code DuplicateRequestException}</li>
 *   <li>{@link Order#place(String, java.util.List, Clock)} — 도메인 invariant (가격 합산, 통화 정합)</li>
 *   <li>OrderRepository.save</li>
 *   <li>OrderPlaced 이벤트를 Outbox 에 INSERT (같은 트랜잭션) — Kafka publish 는 Relay 가 별도</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlaceOrderService implements PlaceOrderUseCase {

    private final OrderRepository orders;
    private final IdempotencyKeyStore idempotencyKeys;
    private final EventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public Order place(PlaceOrderCommand cmd) {
        idempotencyKeys.acquireOrThrow(cmd.idempotencyKey());

        Order order = Order.place(cmd.buyerId(), cmd.toOrderItems(), clock);
        orders.save(order);

        events.publish(order.toPlacedEvent(clock));
        log.info("order placed id={} buyer={} total={}",
                order.id(), order.buyerId(), order.totalAmount());
        return order;
    }
}
