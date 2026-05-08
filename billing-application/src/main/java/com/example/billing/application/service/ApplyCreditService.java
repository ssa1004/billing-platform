package com.example.billing.application.service;

import com.example.billing.application.command.ApplyCreditCommand;
import com.example.billing.application.exception.InvoiceNotFoundException;
import com.example.billing.application.port.in.ApplyCreditUseCase;
import com.example.billing.application.port.out.CreditRepository;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.application.port.out.InvoiceRepository;
import com.example.billing.domain.credit.Credit;
import com.example.billing.domain.invoice.Invoice;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import com.example.billing.domain.shared.Reference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>한 트랜잭션 안이라 Credit 차감과 Invoice 갱신이 원자적입니다. OptimisticLockException
 * 이 발생하면 전체 롤백 → 호출자가 retry (만료 batch / 동시 결제 등과 충돌이 있을 수 있음).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApplyCreditService implements ApplyCreditUseCase {

    private final CreditRepository credits;
    private final InvoiceRepository invoices;
    private final EventPublisher events;
    private final IdempotentExecution idempotency;
    private final Clock clock;

    @Override
    @Transactional
    public Money apply(ApplyCreditCommand cmd) {
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
        }

        log.info("credit applied invoice={} customer={} cap={} applied={} amountDueAfter={}",
                cmd.invoiceId(), customerId, realCap, applied, invoice.amountDue());
        return applied;
    }
}
