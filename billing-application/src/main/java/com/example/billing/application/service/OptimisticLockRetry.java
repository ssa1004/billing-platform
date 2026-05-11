package com.example.billing.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.function.Supplier;

/**
 * 낙관적 락 충돌 (optimistic lock conflict) 이 나면 작은 횟수 안에서 자동으로 다시 시도하는
 * helper.
 *
 * <p><b>낙관적 락이 뭐였는지</b>: 같은 row 를 두 트랜잭션이 동시에 수정하려고 하면 한쪽은
 * {@code @Version} 이 안 맞아 {@code OptimisticLockException} 으로 실패합니다 ("이 row 는
 * 이미 다른 사람이 손대서 너의 version 은 stale 이야"). 충돌이 드물 다고 가정하고 일단
 * 처리한 뒤 충돌 시에만 재시도하는 패턴.</p>
 *
 * <p><b>왜 이 helper 가 필요한가</b>: 도메인 (Credit, Wallet 등) 의 javadoc 에 "충돌 시
 * application service 가 retry" 라고 적혀 있어도 실제로 재시도 코드가 없으면, 동시에 같은
 * Credit 을 차감하는 두 요청 중 한쪽은 그대로 실패합니다. 이 helper 가 그 "service 가 retry"
 * 약속을 실제 코드로 채워줍니다.</p>
 *
 * <p><b>retry budget 은 작게</b> (3회 × 사이 50ms): 충돌이 자주 일어나면 재시도로 해결할
 * 문제가 아니라 경합 자체를 줄이는 설계 변경 (락 분리, 차감을 큐로 직렬화 등) 이 필요한
 * 신호입니다. 자동 retry 는 우연한 충돌만 흡수하기 위한 안전망.</p>
 *
 * <p><b>전제 — 재시도하는 작업은 멱등 (idempotent, 같은 입력 → 같은 결과) 이어야 함</b>:
 * 새 트랜잭션으로 재실행되기 때문에 첫 번째 시도가 외부에 부수 효과 (PG 호출 등) 를 남겼다면
 * 두 번째 시도가 같은 효과를 또 일으키면 안 됩니다. Idempotency-Key 의 rollback 자동 release
 * 와 도메인의 멱등 검사가 같이 동작해야 안전.</p>
 *
 * <p>접근 범위: 같은 패키지 (application.service) 안의 다른 write path 에서도 재사용 (예:
 * ExpireCreditsService 의 만료 batch). 외부 모듈 / adapter 가 직접 호출하는 helper 가 아니라
 * 패키지 내부 유틸이라 package-private 으로 둠.</p>
 */
public final class OptimisticLockRetry {

    private static final Logger log = LoggerFactory.getLogger(OptimisticLockRetry.class);

    private OptimisticLockRetry() {
    }

    public static <T> T withRetry(int maxAttempts, long backoffMillis, Supplier<T> action) {
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
