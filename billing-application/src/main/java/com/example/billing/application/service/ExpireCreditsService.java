package com.example.billing.application.service;

import com.example.billing.application.port.in.ExpireCreditsUseCase;
import com.example.billing.application.port.out.CreditRepository;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.domain.credit.Credit;
import com.example.billing.domain.credit.CreditEvents;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Credit 만료 처리. {@code valid_until <= now} 인 ACTIVE Credit 들을 EXPIRED 로 전이.
 *
 * <p>한 트랜잭션에 한 batch 단위만 처리 — 전체를 한 트랜잭션으로 묶으면 큰 row set 에서
 * lock contention 과 long-running transaction 문제. 호출자 (Spring Batch tasklet 등) 가
 * 결과 0 이 될 때까지 반복.</p>
 *
 * <p><b>낙관적 락 자동 재시도</b>: 같은 Credit 을 동시에 차감하는 결제 (ApplyCreditService)
 * 가 도는 동안 만료 batch 가 돌면 {@code @Version} 충돌 발생 가능. 만료 처리는 *멱등*
 * (이미 EXPIRED 면 {@link Credit#expire} 가 null 을 돌려 skip) 이라 재시도 안전.
 * 충돌 budget 을 넘기면 그대로 throw — 다음 batch run 에서 다시 시도하면 됨.</p>
 */
@Service
@Slf4j
public class ExpireCreditsService implements ExpireCreditsUseCase {

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 50L;

    private final CreditRepository credits;
    private final EventPublisher events;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ExpireCreditsService(CreditRepository credits,
                                EventPublisher events,
                                Clock clock,
                                PlatformTransactionManager txManager) {
        this.credits = credits;
        this.events = events;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public int expireBatch(int limit) {
        return OptimisticLockRetry.withRetry(MAX_RETRY_ATTEMPTS, RETRY_BACKOFF_MILLIS,
                () -> tx.execute(status -> doExpireBatch(limit)));
    }

    private int doExpireBatch(int limit) {
        Instant now = clock.instant();
        List<Credit> candidates = credits.findExpiredCandidates(now, limit);
        if (candidates.isEmpty()) return 0;

        int processed = 0;
        for (Credit credit : candidates) {
            CreditEvents.CreditExpired event = credit.expire(clock);
            if (event == null) continue;   // 이미 종착 (race)
            credits.save(credit);
            events.publish(event);
            processed++;
        }
        log.info("expired credits asOf={} processed={}/{}", now, processed, candidates.size());
        return processed;
    }
}
