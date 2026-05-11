package com.example.billing.application.service;

import com.example.billing.application.command.ApplyCreditCommand;
import com.example.billing.application.exception.InvoiceNotFoundException;
import com.example.billing.application.port.in.ApplyCreditUseCase;
import com.example.billing.application.port.in.AuditLogger;
import com.example.billing.application.port.out.CreditRepository;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.application.port.out.InvoiceRepository;
import com.example.billing.domain.audit.AuditAction;
import com.example.billing.domain.audit.AuditActor;
import com.example.billing.domain.credit.Credit;
import com.example.billing.domain.invoice.Invoice;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import com.example.billing.domain.shared.Reference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Invoice 에 사용 가능한 ACTIVE Credit 들을 합산해서 적용. 한 트랜잭션 안에서:
 * <ol>
 *   <li>Invoice 로드 (DRAFT 거나 종착 상태면 거부)</li>
 *   <li>차감 한도 = min({@code cmd.applyAtMost}, {@code invoice.amountDue()})</li>
 *   <li>{@link CreditRepository#findUsable} 가 정렬해 준 순서대로 차감 — 만료 임박 →
 *       FIFO (먼저 들어온 순)</li>
 *   <li>{@link Invoice#applyCredit(Money)} 로 invoice 의 누적 적용액을 올리고 save</li>
 *   <li>Credit 별로 {@code CreditConsumed} 이벤트를 Outbox 에 INSERT</li>
 * </ol>
 *
 * <p><b>낙관적 락 자동 재시도</b>: Credit/Invoice 의 {@code @Version} 충돌 (만료 batch / 동시
 * 결제 등) 은 {@link OptimisticLockRetry} 로 짧은 budget 안에서 자동 재시도. 충돌이 budget
 * 을 넘기면 {@link org.springframework.dao.OptimisticLockingFailureException} 그대로 throw —
 * 호출자가 후속 처리. Idempotency-Key 는 rollback 훅이 매 시도마다 release 해 주므로
 * 재시도 가능.</p>
 */
@Service
@Slf4j
public class ApplyCreditService implements ApplyCreditUseCase {

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 50L;

    private final CreditRepository credits;
    private final InvoiceRepository invoices;
    private final EventPublisher events;
    private final IdempotentExecution idempotency;
    private final AuditLogger audit;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ApplyCreditService(CreditRepository credits,
                              InvoiceRepository invoices,
                              EventPublisher events,
                              IdempotentExecution idempotency,
                              AuditLogger audit,
                              Clock clock,
                              PlatformTransactionManager txManager) {
        this.credits = credits;
        this.invoices = invoices;
        this.events = events;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public Money apply(ApplyCreditCommand cmd) {
        return OptimisticLockRetry.withRetry(MAX_RETRY_ATTEMPTS, RETRY_BACKOFF_MILLIS,
                () -> tx.execute(status -> doApply(cmd)));
    }

    private Money doApply(ApplyCreditCommand cmd) {
        idempotency.acquireAndReleaseOnRollback(cmd.idempotencyKey());
        Money cap = cmd.applyAtMost();
        if (!cap.isPositive()) {
            return Money.zero(cap.currency());
        }
        Invoice invoice = invoices.findById(cmd.invoiceId())
                .orElseThrow(() -> new InvoiceNotFoundException(cmd.invoiceId()));
        Money due = invoice.amountDue();
        Money realCap = due.compareTo(cap) <= 0 ? due : cap;
        if (!realCap.isPositive()) {
            return Money.zero(cap.currency());
        }

        Instant now = clock.instant();
        CustomerId customerId = CustomerId.of(cmd.customerId());
        List<Credit> usable = credits.findUsable(customerId, now);

        Money applied = Money.zero(cap.currency());
        Reference invoiceRef = Reference.adjustment("invoice:" + cmd.invoiceId());

        for (Credit credit : usable) {
            if (applied.compareTo(realCap) >= 0) break;
            // 통화가 맞지 않으면 skip — Invoice 통화와 다른 Credit 은 환율 변환 없이 적용 안 함
            if (!credit.currency().equals(realCap.currency())) continue;

            Money remainingCap = realCap.subtract(applied);
            Money take = credit.balance().compareTo(remainingCap) <= 0
                    ? credit.balance()
                    : remainingCap;

            var event = credit.consume(take, invoiceRef, clock);
            credits.save(credit);
            events.publish(event);
            applied = applied.add(take);
        }

        if (applied.isPositive()) {
            invoice.applyCredit(applied);
            invoices.save(invoice);

            // Audit — credit 적용은 invoice 의 amountDue 를 줄이는 자금 이동. 회계 감사
            // (어떤 invoice 에 어떤 customer 의 어떤 credit 이 얼마나 들어갔는지) 1차 근거.
            audit.log(
                    AuditActor.system("apply-credit-service"),
                    AuditAction.INVOICE_CREDIT_APPLIED,
                    "Invoice",
                    cmd.invoiceId().toString(),
                    null,
                    String.format("{\"customerId\":\"%s\",\"applied\":\"%s\",\"currency\":\"%s\",\"amountDueAfter\":\"%s\"}",
                            customerId.value(), applied.amount(),
                            applied.currency().getCurrencyCode(), invoice.amountDue().amount()),
                    null
            );
        }

        log.info("credit applied invoice={} customer={} cap={} applied={} amountDueAfter={}",
                cmd.invoiceId(), customerId, realCap, applied, invoice.amountDue());
        return applied;
    }
}
