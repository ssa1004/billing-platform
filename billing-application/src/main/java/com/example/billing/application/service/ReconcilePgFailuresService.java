package com.example.billing.application.service;

import com.example.billing.application.exception.OrderNotFoundException;
import com.example.billing.application.port.in.AuditLogger;
import com.example.billing.application.port.in.ReconcilePgFailuresUseCase;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.application.port.out.OrderRepository;
import com.example.billing.application.port.out.PaymentRepository;
import com.example.billing.application.port.out.PgClient;
import com.example.billing.application.port.out.RefundRepository;
import com.example.billing.domain.audit.AuditAction;
import com.example.billing.domain.audit.AuditActor;
import com.example.billing.domain.order.Order;
import com.example.billing.domain.payment.Payment;
import com.example.billing.domain.payment.PaymentStatus;
import com.example.billing.domain.refund.Refund;
import com.example.billing.domain.refund.RefundStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * PG-failure reconciler — 3-phase 결제/환불 흐름의 phase 3 (DB tx2) 이 깨져 우리 쪽이
 * PENDING/REQUESTED 로 stuck 된 row 들을 발견해 PG lookup 결과로 동기화.
 *
 * <p><b>흐름 — Payment</b>:
 * <ol>
 *   <li>{@code findStalePending(now - graceWindow)} 로 후보 fetch</li>
 *   <li>각 후보에 대해 별도 트랜잭션 안에서:
 *     <ol>
 *       <li>현재 상태 재확인 (race — 다른 호출자가 이미 마감했으면 skip)</li>
 *       <li>{@code pgClient.lookup(idempotencyKey)} → APPROVED / REJECTED / NOT_FOUND / IN_PROGRESS</li>
 *       <li>결과 반영: APPROVED → Payment.approve + Order.markPaid + 이벤트 발행<br>
 *           REJECTED / NOT_FOUND → Payment.reject + Order.markFailed + 이벤트 발행<br>
 *           IN_PROGRESS → 다음 사이클에 재시도 (no-op)</li>
 *     </ol>
 *   </li>
 * </ol>
 *
 * <p><b>흐름 — Refund</b>: 같은 패턴이지만 idempotencyKey 가 null 인 옛날 row 는 lookup 불가
 * 라 SQL 단계에서 제외. RefundCompleted 이벤트의 Wallet 환원 컨슈머는 그대로 동작.
 *
 * <p><b>왜 별도 트랜잭션 (per-row)</b>: 한 후보의 상태 천이가 다른 후보를 막지 않아야 합니다.
 * 한 트랜잭션이 길어지면 lock 보유 시간 증가 + 한 row 가 실패하면 모두 rollback. row 단위
 * 격리로 partial progress.
 *
 * <p><b>graceWindow</b>: phase 1 commit 직후의 정상 동작 중인 Payment 까지 잡지 않도록
 * 충분히 큰 값 (default 5분) 으로 잡음. PG 호출 + tx2 정상 흐름은 보통 수 초.
 */
@Service
@ConditionalOnProperty(name = "billing.pg.reconciler.enabled", havingValue = "true")
@Slf4j
public class ReconcilePgFailuresService implements ReconcilePgFailuresUseCase {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final OrderRepository orders;
    private final PgClient pgClient;
    private final EventPublisher events;
    private final AuditLogger audit;
    private final Clock clock;
    private final TransactionTemplate tx;

    @Value("${billing.pg.reconciler.batch-size:50}")
    private int batchSize;

    @Value("${billing.pg.reconciler.grace-minutes:5}")
    private long graceMinutes;

    public ReconcilePgFailuresService(PaymentRepository payments,
                                      RefundRepository refunds,
                                      OrderRepository orders,
                                      PgClient pgClient,
                                      EventPublisher events,
                                      AuditLogger audit,
                                      Clock clock,
                                      PlatformTransactionManager txManager) {
        this.payments = payments;
        this.refunds = refunds;
        this.orders = orders;
        this.pgClient = pgClient;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public int reconcileBatch() {
        Instant staleBefore = clock.instant().minus(Duration.ofMinutes(graceMinutes));

        // 후보 조회는 readonly tx 와 다르게 분리하지 않고 그냥 select. 본 처리만 per-row tx.
        List<Payment> stalePayments = payments.findStalePending(staleBefore, batchSize);
        List<Refund> staleRefunds = refunds.findStaleRequested(staleBefore, batchSize);

        int processed = 0;
        for (Payment p : stalePayments) {
            try {
                if (reconcilePayment(p.idempotencyKey())) processed++;
            } catch (RuntimeException e) {
                // 한 row 실패가 다음 row 를 막지 않도록 격리. 로그만 남기고 다음 사이클에 재시도.
                log.warn("reconcile payment failed id={} key={}", p.id(), p.idempotencyKey(), e);
            }
        }
        for (Refund r : staleRefunds) {
            try {
                if (reconcileRefund(r.idempotencyKey())) processed++;
            } catch (RuntimeException e) {
                log.warn("reconcile refund failed id={} key={}", r.id(), r.idempotencyKey(), e);
            }
        }
        if (processed > 0) {
            log.info("pg reconcile cycle processed={} payments={} refunds={}",
                    processed, stalePayments.size(), staleRefunds.size());
        }
        return processed;
    }

    private boolean reconcilePayment(String idempotencyKey) {
        // PG lookup 은 트랜잭션 밖 — 외부 호출이 DB connection 점유 안 하도록.
        PgClient.LookupResult lookup = pgClient.lookup(idempotencyKey);
        if (lookup.status() == PgClient.LookupStatus.IN_PROGRESS) {
            // PG 가 아직 결과를 결정 못 함. 다음 사이클에 다시 시도.
            return false;
        }
        Boolean changed = tx.execute(status -> applyPaymentLookup(idempotencyKey, lookup));
        return Boolean.TRUE.equals(changed);
    }

    private boolean applyPaymentLookup(String idempotencyKey, PgClient.LookupResult lookup) {
        Payment payment = payments.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (payment == null) {
            // 후보 select 와 reconcile 사이에 삭제됐거나 키가 변경됨 — race, skip.
            return false;
        }
        if (payment.status() != PaymentStatus.PENDING) {
            // 다른 호출자가 이미 마감 — race, skip.
            return false;
        }
        Order order = orders.findById(payment.orderId())
                .orElseThrow(() -> new OrderNotFoundException(payment.orderId()));

        switch (lookup.status()) {
            case APPROVED -> {
                var approved = payment.approve(lookup.pgReferenceId(), clock);
                payments.save(payment);
                var paid = order.markPaid(payment.id().toString(), clock);
                orders.save(order);
                events.publish(approved);
                events.publish(paid);
                audit.log(
                        AuditActor.system("pg-reconciler"),
                        AuditAction.PAYMENT_RECONCILED,
                        "Payment", payment.id().toString(),
                        null,
                        AuditPayloads.object()
                                .put("resolution", "APPROVED")
                                .put("idempotencyKey", idempotencyKey)
                                .put("pgRef", lookup.pgReferenceId())
                                .build(),
                        "phase 3 retry via PG lookup"
                );
                log.info("reconciled payment APPROVED id={} key={}", payment.id(), idempotencyKey);
            }
            case REJECTED, NOT_FOUND -> {
                String code = lookup.errorCode() != null
                        ? lookup.errorCode()
                        : (lookup.status() == PgClient.LookupStatus.NOT_FOUND ? "PG_NOT_FOUND" : "PG_REJECTED");
                String msg = lookup.errorMessage() != null ? lookup.errorMessage() : "reconciled by lookup";
                var rejected = payment.reject(code, msg, clock);
                payments.save(payment);
                var failed = order.markFailed("payment reconciled: " + msg, clock);
                orders.save(order);
                events.publish(rejected);
                events.publish(failed);
                audit.log(
                        AuditActor.system("pg-reconciler"),
                        AuditAction.PAYMENT_RECONCILED,
                        "Payment", payment.id().toString(),
                        null,
                        AuditPayloads.object()
                                .put("resolution", lookup.status())
                                .put("idempotencyKey", idempotencyKey)
                                .put("errorCode", code)
                                .build(),
                        "phase 3 retry via PG lookup"
                );
                log.info("reconciled payment {} id={} key={}", lookup.status(), payment.id(), idempotencyKey);
            }
            case IN_PROGRESS -> {
                // 위 reconcilePayment 에서 이미 걸렀지만 방어.
                return false;
            }
        }
        return true;
    }

    private boolean reconcileRefund(String idempotencyKey) {
        if (idempotencyKey == null) return false;
        PgClient.LookupResult lookup = pgClient.lookup(idempotencyKey);
        if (lookup.status() == PgClient.LookupStatus.IN_PROGRESS) return false;

        Boolean changed = tx.execute(status -> applyRefundLookup(idempotencyKey, lookup));
        return Boolean.TRUE.equals(changed);
    }

    private boolean applyRefundLookup(String idempotencyKey, PgClient.LookupResult lookup) {
        // Refund 는 idempotency key 로 직접 조회하는 메서드가 아직 없으므로 stale fetch 했던
        // row 를 다시 fetch. select 시점과 reconcile 시점이 떨어져 있어 status 재확인 필수.
        // (Refund 가 적은 빈도라 이중 fetch 가 큰 비용 아님.)
        // 후보 list 안에 같은 키가 두 번 들어올 일은 unique index 로 막음.
        List<Refund> matches = refunds.findStaleRequested(clock.instant(), 1000);
        Refund refund = matches.stream()
                .filter(r -> idempotencyKey.equals(r.idempotencyKey()))
                .findFirst()
                .orElse(null);
        if (refund == null || refund.status() != RefundStatus.REQUESTED) return false;

        switch (lookup.status()) {
            case APPROVED -> {
                var approved = refund.approve(lookup.pgReferenceId(), clock);
                var completed = refund.complete(clock);
                refunds.save(refund);
                events.publish(approved);
                events.publish(completed);
                audit.log(
                        AuditActor.system("pg-reconciler"),
                        AuditAction.REFUND_RECONCILED,
                        "Refund", refund.id().toString(),
                        null,
                        AuditPayloads.object()
                                .put("resolution", "APPROVED")
                                .put("idempotencyKey", idempotencyKey)
                                .put("pgRefundId", lookup.pgReferenceId())
                                .build(),
                        "phase 3 retry via PG lookup"
                );
                log.info("reconciled refund APPROVED id={} key={}", refund.id(), idempotencyKey);
            }
            case REJECTED, NOT_FOUND -> {
                String msg = lookup.errorMessage() != null ? lookup.errorMessage() : "reconciled by lookup";
                var failed = refund.fail(msg, clock);
                refunds.save(refund);
                events.publish(failed);
                audit.log(
                        AuditActor.system("pg-reconciler"),
                        AuditAction.REFUND_RECONCILED,
                        "Refund", refund.id().toString(),
                        null,
                        AuditPayloads.object()
                                .put("resolution", lookup.status())
                                .put("idempotencyKey", idempotencyKey)
                                .build(),
                        "phase 3 retry via PG lookup"
                );
                log.info("reconciled refund {} id={} key={}", lookup.status(), refund.id(), idempotencyKey);
            }
            case IN_PROGRESS -> {
                return false;
            }
        }
        return true;
    }
}
