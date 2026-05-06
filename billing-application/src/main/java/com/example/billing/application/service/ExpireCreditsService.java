package com.example.billing.application.service;

import com.example.billing.application.port.in.ExpireCreditsUseCase;
import com.example.billing.application.port.out.CreditRepository;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.domain.credit.Credit;
import com.example.billing.domain.credit.CreditEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Credit 만료 처리. {@code valid_until <= now} 인 ACTIVE Credit 들을 EXPIRED 로 전이.
 *
 * <p>한 트랜잭션에 한 batch 단위만 처리 — 전체를 한 트랜잭션으로 묶으면 큰 row set 에서
 * lock contention 과 long-running transaction 문제. 호출자 (Spring Batch tasklet 등) 가
 * 결과 0 이 될 때까지 반복.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExpireCreditsService implements ExpireCreditsUseCase {

    private final CreditRepository credits;
    private final EventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public int expireBatch(int limit) {
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
