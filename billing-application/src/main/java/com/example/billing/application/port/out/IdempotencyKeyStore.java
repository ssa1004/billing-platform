package com.example.billing.application.port.out;

/**
 * 멱등성 키 (같은 요청이 두 번 와도 한 번만 처리되게 막는 키) 저장소.
 * 운영에서는 Redis 의 SETNX (key 가 없을 때만 set, 있으면 실패) 로 구현, 로컬에서는
 * in-memory 폴백.
 *
 * <p>{@link #acquireOrThrow} 가 false 면 같은 키로 중복 요청이 들어왔다는 의미 →
 * {@link DuplicateRequestException} 을 던집니다. 같은 요청이 정상 처리됐는지 응답 캐시를
 * 조회하려면 {@link #findCachedResponse} 사용.</p>
 */
public interface IdempotencyKeyStore {

    void acquireOrThrow(String key);

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
