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
 * 환불 use case — PG 환불 호출 + Order / Refund 상태 갱신 + 이벤트 발행.
 * Wallet 환원은 RefundCompleted 이벤트의 컨슈머가 처리 (도메인 분리, decoupled).
 *
 * <p><b>왜 3단계로 쪼개나 (외부 호출을 트랜잭션 밖으로 빼는 이유)</b>:
 * {@link ProcessPaymentService} 와 같은 이유 — 외부 PG 호출 동안 DB 트랜잭션을 열어두면
 * connection 이 pool 에서 빠지지 않아, PG 가 슬로우다운되면 다른 도메인의 트랜잭션까지 같이
 * 멈추는 cascade 가 발생합니다. 흐름을 셋으로 쪼개 외부 호출 동안 connection 을 잡지 않게
 * 합니다:</p>
 * <ol>
 *   <li><b>Phase 1 (DB tx, 짧음)</b>: Idempotency-Key 점유 + Payment 로드 + REQUESTED 상태
 *       Refund row INSERT → commit.</li>
 *   <li><b>PG 환불 호출 (트랜잭션 밖)</b>: connection 미점유 상태로 응답 대기. Resilience4j
 *       서킷브레이커 / Retry 로 보호.</li>
 *   <li><b>Phase 2 (DB tx, 짧음)</b>: PG 결과를 반영해 Refund 상태 천이 + Order REFUNDED 마킹
 *       + 이벤트 발행 + audit log → commit.</li>
 * </ol>
 *
 * <p><b>Phase 2 실패 시</b>: Refund 가 REQUESTED 로 남고 PG 는 이미 환불 처리를 끝낸 상태가
 * 될 수 있습니다. 별도 reconciler 가 stuck REQUESTED row 를 시간 오래된 순으로 스캔해 PG 에
 * 같은 idempotencyKey 로 조회 → 실제 결과로 상태를 동기화 (운영 보강 영역, 본 코드 범위 외;
 * V11 migration 의 인덱스 {@code idx_refund_status_requested_at} 가 그 reconciler 용).</p>
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

        Refund refund = Refund.request(payment.id(), payment.amount(), cmd.reason(),
                cmd.idempotencyKey(), clock);
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
                    AuditPayloads.object()
                            .put("paymentId", ctx.paymentId())
                            .put("amount", refund.amount())
                            .put("pgRefundId", pgResult.pgRefundId())
                            .build(),
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
                    AuditPayloads.object()
                            .put("paymentId", ctx.paymentId())
                            .put("errorMessage", pgResult.errorMessage())
                            .build(),
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
