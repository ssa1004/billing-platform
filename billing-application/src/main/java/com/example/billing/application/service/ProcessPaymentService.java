package com.example.billing.application.service;

import com.example.billing.application.command.ProcessPaymentCommand;
import com.example.billing.application.exception.OrderNotFoundException;
import com.example.billing.application.exception.PaymentNotFoundException;
import com.example.billing.application.port.in.ProcessPaymentUseCase;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.application.port.out.OrderRepository;
import com.example.billing.application.port.out.PaymentRepository;
import com.example.billing.application.port.out.PgClient;
import com.example.billing.domain.order.Order;
import com.example.billing.domain.order.OrderId;
import com.example.billing.domain.payment.Payment;
import com.example.billing.domain.payment.PaymentId;
import com.example.billing.domain.shared.Money;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * 결제 처리 use case — 외부 PG (결제 게이트웨이) 호출 + Order 상태 천이.
 *
 * <p><b>트랜잭션 경계 — PG 호출은 트랜잭션 밖</b>: 외부 호출이 슬로우다운되면 DB connection
 * 이 같이 풀리지 않아 pool 압박 → 다른 트랜잭션도 같이 영향. 그래서 다음 3단계로 쪼갭니다:</p>
 * <ol>
 *   <li><b>Phase 1 (tx)</b>: Idempotency-Key 점유 + Order 로드 + PENDING Payment 영속화</li>
 *   <li><b>PG 호출</b>: 트랜잭션 *밖* — Resilience4j 서킷브레이커 / Retry / 단축 timeout 으로 보호</li>
 *   <li><b>Phase 2 (tx)</b>: Payment / Order 상태 천이 + 이벤트 발행</li>
 * </ol>
 *
 * <p><b>idempotency 의 의미</b>: Phase 1 에서 Idempotency-Key 점유 (TTL ~24h). Phase 1 commit
 * 이후로는 키가 풀리지 않습니다. 이는 *의도적* — 일단 PG 호출이 시작되면 (Phase 1 commit) 결과를
 * 모르기 전에는 같은 키로 재시도하면 안 됨. 호출자는 같은 idempotencyKey 로 GET 해서 결과를
 * 조회하는 패턴.</p>
 *
 * <p><b>Phase 1 실패</b>: 도메인 예외 ({@link OrderNotFoundException}) → tx rollback 으로
 * Idempotency-Key 자동 release → 다른 키로 재시도 가능.</p>
 *
 * <p><b>Phase 2 실패</b>: Payment 가 PENDING 상태로 남아 있고 PG 는 이미 처리됨 → 별도 reconciler
 * 또는 후속 polling 이 PG 에 같은 idempotencyKey 로 *조회* 해서 상태 동기화 (운영 시 별도
 * 보강 필요). 본 코드 범위 외.</p>
 */
@Service
@Slf4j
public class ProcessPaymentService implements ProcessPaymentUseCase {

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final PgClient pgClient;
    private final EventPublisher events;
    private final IdempotentExecution idempotency;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ProcessPaymentService(OrderRepository orders,
                                 PaymentRepository payments,
                                 PgClient pgClient,
                                 EventPublisher events,
                                 IdempotentExecution idempotency,
                                 Clock clock,
                                 PlatformTransactionManager txManager) {
        this.orders = orders;
        this.payments = payments;
        this.pgClient = pgClient;
        this.events = events;
        this.idempotency = idempotency;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public Payment process(ProcessPaymentCommand cmd) {
        // Phase 1 — idempotency 점유 + PENDING Payment 영속화 (외부 호출 없음, 짧은 tx)
        InitiatedContext ctx = tx.execute(status -> initiate(cmd));

        // Phase 2 — PG 호출 (트랜잭션 밖)
        var pgResult = pgClient.authorize(new PgClient.AuthorizeRequest(
                cmd.idempotencyKey(), ctx.amount(), cmd.method(), ctx.orderId().toString()));

        // Phase 3 — 결과 반영 + 이벤트 발행 (짧은 tx)
        return tx.execute(status -> finalize(ctx, pgResult));
    }

    private InitiatedContext initiate(ProcessPaymentCommand cmd) {
        idempotency.acquireAndReleaseOnRollback(cmd.idempotencyKey());

        Order order = orders.findById(cmd.orderId())
                .orElseThrow(() -> new OrderNotFoundException(cmd.orderId()));

        Payment payment = Payment.initiate(order.id(), order.totalAmount(), cmd.method(),
                cmd.idempotencyKey(), clock);
        payments.save(payment);
        return new InitiatedContext(payment.id(), order.id(), order.totalAmount());
    }

    private Payment finalize(InitiatedContext ctx, PgClient.AuthorizeResult pgResult) {
        Payment payment = payments.findById(ctx.paymentId())
                .orElseThrow(() -> new PaymentNotFoundException(ctx.paymentId()));
        Order order = orders.findById(ctx.orderId())
                .orElseThrow(() -> new OrderNotFoundException(ctx.orderId()));

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

    private record InitiatedContext(PaymentId paymentId, OrderId orderId, Money amount) {
    }
}
