package com.example.billing.application.service;

import com.example.billing.application.command.RefundCommand;
import com.example.billing.application.exception.OrderNotFoundException;
import com.example.billing.application.exception.PaymentNotFoundException;
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
import com.example.billing.domain.refund.Refund;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 환불 use case — PG 환불 호출 + Order/Refund 상태 갱신 + 이벤트 발행.
 * Wallet 환원은 RefundCompleted 이벤트의 컨슈머가 처리 (decoupled).
 */
@Service
@RequiredArgsConstructor
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

    @Override
    @Transactional
    public Refund refund(RefundCommand cmd) {
        idempotency.acquireAndReleaseOnRollback(cmd.idempotencyKey());

        Payment payment = payments.findById(cmd.paymentId())
                .orElseThrow(() -> new PaymentNotFoundException(cmd.paymentId()));

        Refund refund = Refund.request(payment.id(), payment.amount(), cmd.reason(), clock);
        refunds.save(refund);

        var pgResult = pgClient.refund(new PgClient.RefundRequest(
                payment.pgTransactionId(), payment.amount(), cmd.reason()));

        if (pgResult.approved()) {
            var approved = refund.approve(pgResult.pgRefundId(), clock);
            var completed = refund.complete(clock);
            refunds.save(refund);

            // Order 상태도 REFUNDED 로
            var order = orders.findById(OrderId.of(payment.orderId().toString()))
                    .orElseThrow(() -> new OrderNotFoundException(payment.orderId()));
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
                            payment.id(), refund.amount(), pgResult.pgRefundId()),
                    cmd.reason()
            );

            log.info("refund completed id={} payment={} amount={}",
                    refund.id(), payment.id(), refund.amount());
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
                            payment.id(), pgResult.errorMessage()),
                    cmd.reason()
            );

            log.warn("refund failed id={} reason={}", refund.id(), pgResult.errorMessage());
        }
        return refund;
    }
}
