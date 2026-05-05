package com.example.billing.application.service;

import com.example.billing.application.command.ProcessPaymentCommand;
import com.example.billing.application.exception.OrderNotFoundException;
import com.example.billing.application.port.in.ProcessPaymentUseCase;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.application.port.out.IdempotencyKeyStore;
import com.example.billing.application.port.out.OrderRepository;
import com.example.billing.application.port.out.PaymentRepository;
import com.example.billing.application.port.out.PgClient;
import com.example.billing.domain.order.Order;
import com.example.billing.domain.payment.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 결제 처리 use case — 외부 PG 호출 + Order 상태 천이.
 *
 * <p>외부 호출(PG)이 트랜잭션 안에 있음 — Wiremock + Resilience4j CB 로 보호. PG 응답에 따라:</p>
 * <ul>
 *   <li>승인 → {@link Payment#approve} + {@link Order#markPaid} + 이벤트 2건 (Outbox)</li>
 *   <li>거절 → {@link Payment#reject} + {@link Order#markFailed} + 이벤트 2건</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProcessPaymentService implements ProcessPaymentUseCase {

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final PgClient pgClient;
    private final EventPublisher events;
    private final IdempotencyKeyStore idempotencyKeys;
    private final Clock clock;

    @Override
    @Transactional
    public Payment process(ProcessPaymentCommand cmd) {
        idempotencyKeys.acquireOrThrow(cmd.idempotencyKey());

        Order order = orders.findById(cmd.orderId())
                .orElseThrow(() -> new OrderNotFoundException(cmd.orderId()));

        Payment payment = Payment.initiate(order.id(), order.totalAmount(), cmd.method(),
                cmd.idempotencyKey(), clock);
        payments.save(payment);

        var pgResult = pgClient.authorize(new PgClient.AuthorizeRequest(
                cmd.idempotencyKey(), order.totalAmount(), cmd.method(), order.id().toString()));

        if (pgResult.approved()) {
            var approved = payment.approve(pgResult.pgTransactionId(), clock);
            payments.save(payment);
            var paid = order.markPaid(payment.id().toString(), clock);
            orders.save(order);
            events.publish(approved);
            events.publish(paid);
            log.info("payment approved id={} order={}", payment.id(), order.id());
        } else {
            var rejected = payment.reject(pgResult.errorCode(), pgResult.errorMessage(), clock);
            payments.save(payment);
            var failed = order.markFailed("payment rejected: " + pgResult.errorMessage(), clock);
            orders.save(order);
            events.publish(rejected);
            events.publish(failed);
            log.warn("payment rejected id={} order={} code={}",
                    payment.id(), order.id(), pgResult.errorCode());
        }
        return payment;
    }
}
