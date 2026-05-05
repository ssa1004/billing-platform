package com.example.wallet.application.port.out;

/**
 * 멱등성 키 저장소 (Redis NX SETNX 구현, fallback in-memory).
 *
 * <p>{@link #acquireOrThrow} 가 false 면 같은 키 중복 요청 → {@link DuplicateRequestException} throw.
 * 같은 요청이 정상 처리됐는지 응답 캐시 조회하려면 {@link #findCachedResponse} 사용.</p>
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
