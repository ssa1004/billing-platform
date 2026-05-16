package com.example.billing.application.service

import com.example.billing.application.command.ApplyCreditCommand
import com.example.billing.application.exception.InvoiceNotFoundException
import com.example.billing.application.port.`in`.ApplyCreditUseCase
import com.example.billing.application.port.`in`.AuditLogger
import com.example.billing.application.port.out.CreditRepository
import com.example.billing.application.port.out.EventPublisher
import com.example.billing.application.port.out.InvoiceRepository
import com.example.billing.domain.audit.AuditAction
import com.example.billing.domain.audit.AuditActor
import com.example.billing.domain.invoice.Invoice
import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.shared.Money
import com.example.billing.domain.shared.Reference
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * Invoice 에 사용 가능한 ACTIVE Credit 들을 합산해서 적용. 한 트랜잭션 안에서:
 *  1. Invoice 로드 (DRAFT 거나 종착 상태면 거부)
 *  2. 차감 한도 = min(`cmd.applyAtMost`, `invoice.amountDue()`)
 *  3. [CreditRepository.findUsable] 가 정렬해 준 순서대로 차감 — 만료 임박 →
 *     FIFO (먼저 들어온 순)
 *  4. [Invoice.applyCredit] 로 invoice 의 누적 적용액을 올리고 save
 *  5. Credit 별로 `CreditConsumed` 이벤트를 Outbox 에 INSERT
 *
 * **낙관적 락 자동 재시도**: Credit/Invoice 의 `@Version` 충돌 (만료 batch / 동시
 * 결제 등) 은 [OptimisticLockRetry] 로 짧은 budget 안에서 자동 재시도. 충돌이 budget
 * 을 넘기면 [org.springframework.dao.OptimisticLockingFailureException] 그대로 throw —
 * 호출자가 후속 처리. Idempotency-Key 는 rollback 훅이 매 시도마다 release 해 주므로
 * 재시도 가능.
 */
@Service
open class ApplyCreditService(
    private val credits: CreditRepository,
    private val invoices: InvoiceRepository,
    private val events: EventPublisher,
    private val idempotency: IdempotentExecution,
    private val audit: AuditLogger,
    private val clock: Clock,
    txManager: PlatformTransactionManager,
) : ApplyCreditUseCase {

    private val tx = TransactionTemplate(txManager)

    override fun apply(command: ApplyCreditCommand): Money =
        OptimisticLockRetry.withRetry(MAX_RETRY_ATTEMPTS, RETRY_BACKOFF_MILLIS) {
            tx.execute { doApply(command) } ?: Money.zero(command.applyAtMost.currency)
        }

    private fun doApply(cmd: ApplyCreditCommand): Money {
        idempotency.acquireAndReleaseOnRollback(cmd.idempotencyKey)
        val cap = cmd.applyAtMost
        if (!cap.isPositive) {
            return Money.zero(cap.currency)
        }
        val invoice: Invoice = invoices.findById(cmd.invoiceId)
            .orElseThrow { InvoiceNotFoundException(cmd.invoiceId) }
        val due = invoice.amountDue()
        val realCap = if (due.compareTo(cap) <= 0) due else cap
        if (!realCap.isPositive) {
            return Money.zero(cap.currency)
        }

        val now = clock.instant()
        val customerId = CustomerId.of(cmd.customerId)
        val usable = credits.findUsable(customerId, now)

        var applied = Money.zero(cap.currency)
        val invoiceRef = Reference.adjustment("invoice:${cmd.invoiceId}")

        for (credit in usable) {
            if (applied.compareTo(realCap) >= 0) break
            // 통화가 맞지 않으면 skip — Invoice 통화와 다른 Credit 은 환율 변환 없이 적용 안 함
            if (credit.currency != realCap.currency) continue

            val remainingCap = realCap.subtract(applied)
            val take = if (credit.balance.compareTo(remainingCap) <= 0) credit.balance else remainingCap

            val event = credit.consume(take, invoiceRef, clock)
            credits.save(credit)
            events.publish(event)
            applied = applied.add(take)
        }

        if (applied.isPositive) {
            invoice.applyCredit(applied)
            invoices.save(invoice)

            // Audit — credit 적용은 invoice 의 amountDue 를 줄이는 자금 이동. 회계 감사
            // (어떤 invoice 에 어떤 customer 의 어떤 credit 이 얼마나 들어갔는지) 1차 근거.
            audit.log(
                AuditActor.system("apply-credit-service"),
                AuditAction.INVOICE_CREDIT_APPLIED,
                "Invoice",
                cmd.invoiceId.toString(),
                null,
                AuditPayloads.`object`()
                    .put("customerId", customerId.value)
                    .put("applied", applied.amount)
                    .put("currency", applied.currency.currencyCode)
                    .put("amountDueAfter", invoice.amountDue().amount)
                    .build(),
                null,
            )
        }

        log.info(
            "credit applied invoice={} customer={} cap={} applied={} amountDueAfter={}",
            cmd.invoiceId, customerId, realCap, applied, invoice.amountDue(),
        )
        return applied
    }

    companion object {
        private val log = LoggerFactory.getLogger(ApplyCreditService::class.java)
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MILLIS = 50L
    }
}
