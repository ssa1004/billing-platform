package com.example.billing.application.service

import com.example.billing.application.port.`in`.AuditLogger
import com.example.billing.application.port.`in`.SoftDeleteUseCase
import com.example.billing.application.port.out.InvoiceRepository
import com.example.billing.application.port.out.PaymentRepository
import com.example.billing.application.port.out.RefundRepository
import com.example.billing.domain.audit.AuditAction
import com.example.billing.domain.audit.AuditActor
import com.example.billing.domain.invoice.Invoice
import com.example.billing.domain.payment.Payment
import com.example.billing.domain.payment.PaymentId
import com.example.billing.domain.refund.Refund
import com.example.billing.domain.refund.RefundId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 회계 도메인 row 의 논리 삭제 + audit 발행 (ADR-0030).
 *
 * 한 트랜잭션에서:
 *  1. 대상 row 조회 (활성 row 만)
 *  2. `repository.softDelete(id, deletedBy)` — UPDATE 한 번으로 deleted_at + deleted_by 마킹
 *  3. SOFT_DELETED audit 발행 (before=row JSON, after=null, reason=호출자 사유)
 *
 * **왜 row 조회를 먼저?** audit 의 beforeJson 에 삭제 시점의 row 스냅샷 을 박아넣기 위해.
 * 조회 → 삭제 사이에 다른 트랜잭션이 같은 row 를 또 삭제할 수는 없습니다 (UPDATE 가 멱등 — 두 번째 호출은 0행 영향).
 * 트랜잭션 격리 수준 (READ COMMITTED / REPEATABLE READ) 에 상관없이 SOFT_DELETED audit 가
 * 두 번 발행되지 않도록 `softDelete` 가 boolean 으로 "이미 삭제됐는지" 알려줍니다.
 *
 * **왜 propagation REQUIRED?** 호출자 (운영자 화면 컨트롤러) 가 자기 트랜잭션을 갖고 있을
 * 수 있는데, 그 안에서 우리 작업이 부분 commit 되면 곤란. 호출자 트랜잭션이 rollback 되면 우리
 * 마킹도 rollback 되어야 대응되는 audit entry 도 같이 사라집니다.
 */
@Service
open class SoftDeleteService(
    private val invoices: InvoiceRepository,
    private val payments: PaymentRepository,
    private val refunds: RefundRepository,
    private val auditLogger: AuditLogger,
) : SoftDeleteUseCase {

    @Transactional(propagation = Propagation.REQUIRED)
    override fun softDeleteInvoice(invoiceId: UUID, actor: AuditActor, reason: String): Boolean {
        val before = invoices.findById(invoiceId)
        if (before.isEmpty) {
            // 활성 row 가 없음 — 이미 삭제됐거나 존재한 적 없음. 멱등하게 false.
            log.info("[soft-delete] invoice not found or already deleted: {}", invoiceId)
            return false
        }
        val affected = invoices.softDelete(invoiceId, actor.id)
        if (!affected) {
            // 동시성 — 다른 트랜잭션이 먼저 삭제. audit 도 그쪽에서 발행됐을 것이라 우리는 skip.
            return false
        }
        auditLogger.log(
            actor, AuditAction.SOFT_DELETED, "Invoice", invoiceId.toString(),
            summarize(before.get()), null, reason,
        )
        return true
    }

    @Transactional(propagation = Propagation.REQUIRED)
    override fun softDeletePayment(paymentId: PaymentId, actor: AuditActor, reason: String): Boolean {
        val before = payments.findById(paymentId)
        if (before.isEmpty) {
            log.info("[soft-delete] payment not found or already deleted: {}", paymentId)
            return false
        }
        val affected = payments.softDelete(paymentId, actor.id)
        if (!affected) return false
        auditLogger.log(
            actor, AuditAction.SOFT_DELETED, "Payment", paymentId.value.toString(),
            summarize(before.get()), null, reason,
        )
        return true
    }

    @Transactional(propagation = Propagation.REQUIRED)
    override fun softDeleteRefund(refundId: RefundId, actor: AuditActor, reason: String): Boolean {
        val before = refunds.findById(refundId)
        if (before.isEmpty) {
            log.info("[soft-delete] refund not found or already deleted: {}", refundId)
            return false
        }
        val affected = refunds.softDelete(refundId, actor.id)
        if (!affected) return false
        auditLogger.log(
            actor, AuditAction.SOFT_DELETED, "Refund", refundId.value.toString(),
            summarize(before.get()), null, reason,
        )
        return true
    }

    companion object {
        private val log = LoggerFactory.getLogger(SoftDeleteService::class.java)

        // ── before-snapshot 직렬화 ──
        // 운영 표준은 Jackson + 도메인별 DTO 지만, audit 의 before/after 는 디버깅 / forensic 용
        // 이라 아주 상세할 필요는 없음. 핵심 식별자 + 상태 + 금액만 평면(flat) JSON 으로 압축.
        // customerId / pgTransactionId / pgRefundId 는 외부 문자열이라 AuditPayloads 가 escape.

        private fun summarize(i: Invoice): String =
            AuditPayloads.`object`()
                .put("id", i.id)
                .put("customerId", i.customerId.value)
                .put("period", i.period.toKey())
                .put("status", i.status)
                .put("total", i.total.amount.toString() + " " + i.total.currency.currencyCode)
                .build()

        private fun summarize(p: Payment): String =
            AuditPayloads.`object`()
                .put("id", p.id.value)
                .put("orderId", p.orderId)
                .put("amount", p.amount.amount.toString() + " " + p.amount.currency.currencyCode)
                .put("status", p.status)
                .put("pgTransactionId", p.pgTransactionId ?: "")
                .build()

        private fun summarize(r: Refund): String =
            AuditPayloads.`object`()
                .put("id", r.id.value)
                .put("paymentId", r.paymentId.value)
                .put("amount", r.amount.amount.toString() + " " + r.amount.currency.currencyCode)
                .put("status", r.status)
                .put("pgRefundId", r.pgRefundId ?: "")
                .build()
    }
}
