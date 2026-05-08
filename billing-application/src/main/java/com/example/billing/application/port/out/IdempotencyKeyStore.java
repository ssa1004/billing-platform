package com.example.billing.application.port.out;

import java.util.Optional;

/**
 * 멱등성 키 (같은 요청이 두 번 와도 한 번만 처리되게 막는 키) 저장소.
 * 운영에서는 Redis 의 SETNX (key 가 없을 때만 set, 있으면 실패) 로 구현, 로컬에서는
 * in-memory 폴백.
 *
 * <p><b>두 종류의 상태를 같이 다룸</b>:</p>
 * <ul>
 *   <li><b>점유 lock</b>: {@link #acquireOrThrow} 가 false 면 같은 키로 중복 요청이 들어왔다는 의미 →
 *       {@link DuplicateRequestException} 을 던집니다.</li>
 *   <li><b>응답 캐시</b>: {@link #cacheResponse} 로 저장 → {@link #findCachedResponse} 로 조회.
 *       Stripe / 토스페이먼츠 / iamport 모두 같은 idempotencyKey 로 재시도 시 *처음 응답 그대로*
 *       반환합니다 (24h). 결제 / 환불처럼 client 가 timeout 으로 retry 해도 두 번째 요청이
 *       똑같은 응답을 받아야 정합. ADR-0024 참고.</li>
 * </ul>
 *
 * <p><b>롤백 시 release</b>: 트랜잭션이 rollback 되면 도메인 변경은 사라지지만 Redis 락은
 * 남아 있어, 같은 키로 재시도하면 {@link DuplicateRequestException} 만 계속 떨어집니다.
 * application service 는 lock 획득 직후
 * {@link com.example.billing.application.service.IdempotentExecution} 으로 rollback 훅을
 * 등록해, 트랜잭션이 실패 (예: 도메인 검증 실패 / 외부 PG 실패) 하면 키도 같이 풀어줘야
 * 합니다.</p>
 */
public interface IdempotencyKeyStore {

    void acquireOrThrow(String key);

    /**
     * 점유 해제. 일반적으로는 호출하지 않습니다 — TTL 로 만료. 단,
     * <ul>
     *   <li>같은 트랜잭션이 rollback 되어 도메인 변경이 안 되었을 때</li>
     *   <li>운영자가 수동으로 풀어야 할 때</li>
     * </ul>
     * 호출됩니다. 이미 없는 키여도 예외 없이 통과 (idempotent).
     */
    void release(String key);

    /**
     * 결과 캐싱 — 같은 키 재호출 시 같은 응답 반환.
     *
     * <p>본문이 {@link #MAX_BODY_BYTES} 를 넘으면 호출자가 미리 cap (잘라내거나 cache skip).
     * 구현체는 cap 보다 큰 입력에 대해 *조용히 무시* 하지 않고 호출자가 결정한 본문을 그대로
     * 저장합니다.</p>
     */
    void cacheResponse(String key, int httpStatus, String body);

    /**
     * 캐시된 응답 조회. 점유만 된 (response 가 아직 안 박힌) 키는 {@link Optional#empty()}.
     */
    Optional<CachedResponse> findCachedResponse(String key);

    /**
     * 응답 본문 캐시 상한 — 16KB. 이를 넘는 응답은 cache skip (처리 중 응답 그대로 통과시킴).
     *
     * <p>왜 16KB 인가: Stripe 가 명세상 limit 을 두지는 않지만 실측 응답이 1~2KB 수준.
     * 결제 / 환불 응답도 비슷. 16KB 면 거의 모든 정상 응답을 cover 하면서 대형 streaming 응답
     * (PDF, CSV) 을 자연스럽게 우회.</p>
     */
    int MAX_BODY_BYTES = 16 * 1024;

    record CachedResponse(int status, String body) {}

    class DuplicateRequestException extends RuntimeException {
        public DuplicateRequestException(String key) {
            super("duplicate request: idempotencyKey=" + key);
        }
    }
}
