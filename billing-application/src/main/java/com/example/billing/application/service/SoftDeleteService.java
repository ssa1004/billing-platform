package com.example.billing.application.service;

import com.example.billing.application.port.in.AuditLogger;
import com.example.billing.application.port.in.SoftDeleteUseCase;
import com.example.billing.application.port.out.InvoiceRepository;
import com.example.billing.application.port.out.PaymentRepository;
import com.example.billing.application.port.out.RefundRepository;
import com.example.billing.domain.audit.AuditAction;
import com.example.billing.domain.audit.AuditActor;
import com.example.billing.domain.invoice.Invoice;
import com.example.billing.domain.payment.Payment;
import com.example.billing.domain.payment.PaymentId;
import com.example.billing.domain.refund.Refund;
import com.example.billing.domain.refund.RefundId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 회계 도메인 row 의 논리 삭제 + audit 발행 (ADR-0030).
 *
 * <p>한 트랜잭션에서:
 * <ol>
 *   <li>대상 row 조회 (활성 row 만)</li>
 *   <li>{@code repository.softDelete(id, deletedBy)} — UPDATE 한 번으로 deleted_at + deleted_by 마킹</li>
 *   <li>SOFT_DELETED audit 발행 (before=row JSON, after=null, reason=호출자 사유)</li>
 * </ol>
 *
 * <p><b>왜 row 조회를 먼저?</b> audit 의 beforeJson 에 삭제 시점의 row 스냅샷 을 박아넣기 위해.
 * 조회 → 삭제 사이에 다른 트랜잭션이 같은 row 를 또 삭제할 수는 없습니다 (UPDATE 가 멱등 — 두 번째 호출은 0행 영향).
 * 트랜잭션 격리 수준 (READ COMMITTED / REPEATABLE READ) 에 상관없이 SOFT_DELETED audit 가
 * 두 번 발행되지 않도록 {@code softDelete} 가 boolean 으로 "이미 삭제됐는지" 알려줍니다.</p>
 *
 * <p><b>왜 propagation REQUIRED?</b> 호출자 (운영자 화면 컨트롤러) 가 자기 트랜잭션을 갖고 있을
 * 수 있는데, 그 안에서 우리 작업이 부분 commit 되면 곤란. 호출자 트랜잭션이 rollback 되면 우리
 * 마킹도 rollback 되어야 대응되는 audit entry 도 같이 사라집니다.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SoftDeleteService implements SoftDeleteUseCase {

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final AuditLogger auditLogger;

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean softDeleteInvoice(UUID invoiceId, AuditActor actor, String reason) {
        Optional<Invoice> before = invoices.findById(invoiceId);
        if (before.isEmpty()) {
            // 활성 row 가 없음 — 이미 삭제됐거나 존재한 적 없음. 멱등하게 false.
            log.info("[soft-delete] invoice not found or already deleted: {}", invoiceId);
            return false;
        }
        boolean affected = invoices.softDelete(invoiceId, actor.id());
        if (!affected) {
            // 동시성 — 다른 트랜잭션이 먼저 삭제. audit 도 그쪽에서 발행됐을 것이라 우리는 skip.
            return false;
        }
        auditLogger.log(actor, AuditAction.SOFT_DELETED, "Invoice", invoiceId.toString(),
                summarize(before.get()), null, reason);
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean softDeletePayment(PaymentId paymentId, AuditActor actor, String reason) {
        Optional<Payment> before = payments.findById(paymentId);
        if (before.isEmpty()) {
            log.info("[soft-delete] payment not found or already deleted: {}", paymentId);
            return false;
        }
        boolean affected = payments.softDelete(paymentId, actor.id());
        if (!affected) return false;
        auditLogger.log(actor, AuditAction.SOFT_DELETED, "Payment", paymentId.value().toString(),
                summarize(before.get()), null, reason);
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean softDeleteRefund(RefundId refundId, AuditActor actor, String reason) {
        Optional<Refund> before = refunds.findById(refundId);
        if (before.isEmpty()) {
            log.info("[soft-delete] refund not found or already deleted: {}", refundId);
            return false;
        }
        boolean affected = refunds.softDelete(refundId, actor.id());
        if (!affected) return false;
        auditLogger.log(actor, AuditAction.SOFT_DELETED, "Refund", refundId.value().toString(),
                summarize(before.get()), null, reason);
        return true;
    }

    // ── before-snapshot 직렬화 ──
    // 운영 표준은 Jackson + 도메인별 DTO 지만, audit 의 before/after 는 디버깅 / forensic 용
    // 이라 아주 상세할 필요는 없음. 핵심 식별자 + 상태 + 금액만 toString 으로 압축.

    private static String summarize(Invoice i) {
        return String.format(
                "{\"id\":\"%s\",\"customerId\":\"%s\",\"period\":\"%s\",\"status\":\"%s\","
                        + "\"total\":\"%s %s\"}",
                i.id(), i.customerId().value(), i.period().toKey(),
                i.status(), i.total().amount(), i.total().currency().getCurrencyCode());
    }

    private static String summarize(Payment p) {
        return String.format(
                "{\"id\":\"%s\",\"orderId\":\"%s\",\"amount\":\"%s %s\",\"status\":\"%s\","
                        + "\"pgTransactionId\":\"%s\"}",
                p.id().value(), p.orderId(), p.amount().amount(),
                p.amount().currency().getCurrencyCode(),
                p.status(), p.pgTransactionId() == null ? "" : p.pgTransactionId());
    }

    private static String summarize(Refund r) {
        return String.format(
                "{\"id\":\"%s\",\"paymentId\":\"%s\",\"amount\":\"%s %s\",\"status\":\"%s\","
                        + "\"pgRefundId\":\"%s\"}",
                r.id().value(), r.paymentId().value(),
                r.amount().amount(), r.amount().currency().getCurrencyCode(),
                r.status(), r.pgRefundId() == null ? "" : r.pgRefundId());
    }
}
