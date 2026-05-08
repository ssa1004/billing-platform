package com.example.billing.application.port.out;

/**
 * 멱등성 키 (같은 요청이 두 번 와도 한 번만 처리되게 막는 키) 저장소.
 * 운영에서는 Redis 의 SETNX (key 가 없을 때만 set, 있으면 실패) 로 구현, 로컬에서는
 * in-memory 폴백.
 *
 * <p>{@link #acquireOrThrow} 가 false 면 같은 키로 중복 요청이 들어왔다는 의미 →
 * {@link DuplicateRequestException} 을 던집니다. 같은 요청이 정상 처리됐는지 응답 캐시를
 * 조회하려면 {@link #findCachedResponse} 사용.</p>
 *
 * <p><b>롤백 시 release</b>: 트랜잭션이 rollback 되면 도메인 변경은 사라지지만 Redis 락은
 * 남아 있어, 같은 키로 재시도하면 {@link DuplicateRequestException} 만 계속 떨어집니다.
 * application service 는 lock 획득 직후 {@link #releaseOnRollback} 으로 rollback 훅을
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

    /** 결과 캐싱 — 같은 키 재호출 시 같은 응답 반환 (옵션). */
    void cacheResponse(String key, int httpStatus, String body);

    java.util.Optional<CachedResponse> findCachedResponse(String key);

    record CachedResponse(int status, String body) {}

    class DuplicateRequestException extends RuntimeException {
        public DuplicateRequestException(String key) {
            super("duplicate request: idempotencyKey=" + key);
        }
    }
}
