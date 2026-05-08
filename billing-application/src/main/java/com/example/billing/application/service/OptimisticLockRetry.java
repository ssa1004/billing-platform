package com.example.billing.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.function.Supplier;

/**
 * 낙관적 락 충돌 발생 시 짧은 재시도 budget 안에서 재실행하는 helper.
 *
 * <p>도메인 (Credit, Wallet 등) 의 javadoc 은 "OptimisticLockException 발생 시 application
 * service 가 retry" 라고 적혀 있는데, 실제로 자동 재시도가 도입되어 있지 않으면 동시 차감 race
 * 에서 한쪽 호출자가 그대로 실패합니다. 이 helper 가 그 약속을 코드로 채워줍니다.</p>
 *
 * <p><b>retry budget</b>: 작게 잡습니다 (3회 / 사이 50ms). 충돌이 자주 일어나면 재시도로
 * 해결될 일이 아니라 *경합 자체를 줄이는 설계 변경* (락 분리, 비차감 처리 등) 이 필요합니다.
 * 자동 retry 는 *우연한* 충돌만 흡수하기 위한 안전장치.</p>
 *
 * <p><b>주의</b>: 재시도하는 작업은 *반드시 멱등* (같은 입력 → 같은 결과) 이어야 합니다.
 * Idempotency-Key 점유 + 새로운 트랜잭션으로 재실행되므로, 도메인이 idempotency 체크를
 * 제대로 하고 있어야 안전합니다.</p>
 */
final class OptimisticLockRetry {

    private static final Logger log = LoggerFactory.getLogger(OptimisticLockRetry.class);

    private OptimisticLockRetry() {
    }

    static <T> T withRetry(int maxAttempts, long backoffMillis, Supplier<T> action) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        OptimisticLockingFailureException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (OptimisticLockingFailureException e) {
                last = e;
                log.warn("optimistic lock conflict attempt={}/{} retrying", attempt, maxAttempts);
                if (attempt < maxAttempts) {
                    sleep(backoffMillis);
                }
            }
        }
        throw last;
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
