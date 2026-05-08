package com.example.billing.application.service;

import com.example.billing.application.command.RefundCommand;
import com.example.billing.application.exception.OrderNotFoundException;
import com.example.billing.application.exception.PaymentNotFoundException;
import com.example.billing.application.exception.RefundNotFoundException;
import com.example.billing.application.port.in.AuditLogger;
import com.example.billing.application.port.in.RefundUseCase;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.application.port.out.OrderRepository;
import com.example.billing.application.port.out.PaymentRepository;
import com.example.billing.application.port.out.PgClient;
import com.example.billing.application.port.out.RefundRepository;
import com.example.billing.domain.audit.AuditAction;
import com.example.billing.domain.audit.AuditActor;
import com.example.billing.domain.order.OrderId;
import com.example.billing.domain.payment.Payment;
import com.example.billing.domain.payment.PaymentId;
import com.example.billing.domain.refund.Refund;
import com.example.billing.domain.refund.RefundId;
import com.example.billing.domain.shared.Money;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * 환불 use case — PG 환불 호출 + Order/Refund 상태 갱신 + 이벤트 발행.
 * Wallet 환원은 RefundCompleted 이벤트의 컨슈머가 처리 (decoupled).
 *
 * <p><b>트랜잭션 경계 — PG 호출은 트랜잭션 밖</b>: PG 가 슬로우다운되면 DB connection 이 같이
 * 풀리지 않아 pool 압박 → 다른 트랜잭션도 영향. 그래서 다음 3단계로 분리:</p>
 * <ol>
 *   <li><b>Phase 1 (tx)</b>: Idempotency-Key 점유 + Payment 로드 + REQUESTED Refund 영속화</li>
 *   <li><b>PG 환불 호출</b>: 트랜잭션 *밖* — Resilience4j 서킷브레이커 / Retry 로 보호</li>
 *   <li><b>Phase 2 (tx)</b>: Refund 상태 천이 + Order REFUNDED 마킹 + 이벤트 + audit</li>
 * </ol>
 *
 * <p>Phase 2 가 실패하면 Refund 가 REQUESTED 로 남고 PG 는 이미 환불을 처리한 상태가 될 수
 * 있습니다 — 별도 reconciler 가 PG 와 상태 동기화 (운영 시 보강). 본 코드 범위 외.</p>
 */
@Service
@Slf4j
public class RefundService implements RefundUseCase {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final OrderRepository orders;
    private final PgClient pgClient;
    private final EventPublisher events;
    private final IdempotentExecution idempotency;
    private final AuditLogger audit;
    private final Clock clock;
    private final TransactionTemplate tx;

    public RefundService(PaymentRepository payments,
                         RefundRepository refunds,
                         OrderRepository orders,
                         PgClient pgClient,
                         EventPublisher events,
                         IdempotentExecution idempotency,
                         AuditLogger audit,
                         Clock clock,
                         PlatformTransactionManager txManager) {
        this.payments = payments;
        this.refunds = refunds;
        this.orders = orders;
        this.pgClient = pgClient;
        this.events = events;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public Refund refund(RefundCommand cmd) {
        // Phase 1 — idempotency 점유 + REQUESTED Refund 영속화
        InitiatedContext ctx = tx.execute(status -> initiate(cmd));

        // Phase 2 — PG 환불 호출 (트랜잭션 밖)
        var pgResult = pgClient.refund(new PgClient.RefundRequest(
                ctx.pgTransactionId(), ctx.amount(), cmd.reason()));

        // Phase 3 — 결과 반영 + 이벤트 + audit (짧은 tx)
        return tx.execute(status -> finalize(cmd, ctx, pgResult));
    }

    private InitiatedContext initiate(RefundCommand cmd) {
        idempotency.acquireAndReleaseOnRollback(cmd.idempotencyKey());

        Payment payment = payments.findById(cmd.paymentId())
                .orElseThrow(() -> new PaymentNotFoundException(cmd.paymentId()));

        Refund refund = Refund.request(payment.id(), payment.amount(), cmd.reason(), clock);
        refunds.save(refund);
        return new InitiatedContext(refund.id(), payment.id(),
                payment.pgTransactionId(), payment.amount(), payment.orderId());
    }

    private Refund finalize(RefundCommand cmd, InitiatedContext ctx,
                            PgClient.RefundResult pgResult) {
        Refund refund = refunds.findById(ctx.refundId())
                .orElseThrow(() -> new RefundNotFoundException(ctx.refundId()));

        if (pgResult.approved()) {
            var approved = refund.approve(pgResult.pgRefundId(), clock);
            var completed = refund.complete(clock);
            refunds.save(refund);

            // Order 상태도 REFUNDED 로
            var order = orders.findById(ctx.orderId())
                    .orElseThrow(() -> new OrderNotFoundException(ctx.orderId()));
            var orderRefunded = order.markRefunded(refund.id().toString(), clock);
            orders.save(order);

            events.publish(approved);
            events.publish(completed);
            events.publish(orderRefunded);

            // Audit — 환불 승인은 돈이 customer 로 빠져나가는 동작이라 audit 대상.
            // 회계 감사 시 "이 환불이 왜 승인됐는지" 답할 수 있어야 함.
            audit.log(
                    AuditActor.system("refund-service"),
                    AuditAction.REFUND_APPROVED,
                    "Refund",
                    refund.id().toString(),
                    null,
                    "{\"paymentId\":\"%s\",\"amount\":\"%s\",\"pgRefundId\":\"%s\"}".formatted(
                            ctx.paymentId(), refund.amount(), pgResult.pgRefundId()),
                    cmd.reason()
            );

            log.info("refund completed id={} payment={} amount={}",
                    refund.id(), ctx.paymentId(), refund.amount());
        } else {
            var failed = refund.fail(pgResult.errorMessage(), clock);
            refunds.save(refund);
            events.publish(failed);

            audit.log(
                    AuditActor.system("refund-service"),
                    AuditAction.REFUND_FAILED,
                    "Refund",
                    refund.id().toString(),
                    null,
                    "{\"paymentId\":\"%s\",\"errorMessage\":\"%s\"}".formatted(
                            ctx.paymentId(), pgResult.errorMessage()),
                    cmd.reason()
            );

            log.warn("refund failed id={} reason={}", refund.id(), pgResult.errorMessage());
        }
        return refund;
    }

    private record InitiatedContext(RefundId refundId, PaymentId paymentId,
                                    String pgTransactionId, Money amount, OrderId orderId) {
    }
}
