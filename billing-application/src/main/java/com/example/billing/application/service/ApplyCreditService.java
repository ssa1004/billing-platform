package com.example.billing.application.service;

import com.example.billing.application.command.ApplyCreditCommand;
import com.example.billing.application.port.in.ApplyCreditUseCase;
import com.example.billing.application.port.out.CreditRepository;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.domain.credit.Credit;
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
 * 사용 가능한 ACTIVE Credit 들을 합산해 차감. {@code applyAtMost} 한도까지만.
 *
 * <p>차감 우선순위 ({@code CreditRepository.findUsable} 가 정렬):
 * <ol>
 *   <li>만료 임박한 것 (만료 손실 최소화)</li>
 *   <li>같은 만료라면 발급 시점 빠른 것 (FIFO)</li>
 * </ol>
 *
 * <p>Invoice 와의 연동은 호출 측이 책임 — 이 service 는 차감만 한다. invoice 의 결제 대상
 * 금액 갱신, ledger 기록 등은 별도. 트랜잭션은 호출-당-1 (same-thread) 라 OK.</p>
 *
 * <p>한 트랜잭션 안에서 여러 Credit 을 차감하다 OptimisticLock 발생 시 전체 롤백 →
 * 호출자가 retry. (만료 batch 와 동시 충돌 가능)</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApplyCreditService implements ApplyCreditUseCase {

    private final CreditRepository credits;
    private final EventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public Money apply(ApplyCreditCommand cmd) {
        Money cap = cmd.applyAtMost();
        if (!cap.isPositive()) {
            return Money.zero(cap.currency());
        }
        Instant now = clock.instant();
        CustomerId customerId = CustomerId.of(cmd.customerId());
        List<Credit> usable = credits.findUsable(customerId, now);

        Money applied = Money.zero(cap.currency());
        Reference invoiceRef = Reference.adjustment("invoice:" + cmd.invoiceId());

        for (Credit credit : usable) {
            if (applied.compareTo(cap) >= 0) break;
            // 통화가 다르면 skip — Invoice 통화와 다른 Credit 은 적용 X
            if (!credit.currency().equals(cap.currency())) continue;

            Money remainingCap = cap.subtract(applied);
            Money take = credit.balance().compareTo(remainingCap) <= 0
                    ? credit.balance()
                    : remainingCap;

            var event = credit.consume(take, invoiceRef, clock);
            credits.save(credit);
            events.publish(event);
            applied = applied.add(take);
        }

        log.info("credit applied invoice={} customer={} cap={} applied={}",
                cmd.invoiceId(), customerId, cap, applied);
        return applied;
    }
}
