package com.example.billing.adapter.out.persistence.idempotency;

import com.example.billing.application.port.out.IdempotencyKeyStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory 멱등성 키 저장소 — 로컬 dev 전용. {@code billing.cache.redis-enabled=false} 일 때 활성.
 * 운영은 {@link RedisIdempotencyKeyStore} (Redis NX SETNX).
 */
@Component
@ConditionalOnProperty(name = "billing.cache.redis-enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryIdempotencyKeyStore implements IdempotencyKeyStore {

    private final ConcurrentMap<String, CachedResponse> store = new ConcurrentHashMap<>();
    /** 키 → fingerprint (별도 map — response cache 와 lifecycle 다름). */
    private final ConcurrentMap<String, String> fingerprints = new ConcurrentHashMap<>();
    private static final CachedResponse PLACEHOLDER = new CachedResponse(0, "");

    @Override
    public void acquireOrThrow(String key) {
        if (store.putIfAbsent(key, PLACEHOLDER) != null) {
            throw new DuplicateRequestException(key);
        }
    }

    @Override
    public void release(String key) {
        // 캐시된 응답이 이미 있다면 그대로 두고 (재호출 시 같은 응답 반환), placeholder 만 제거.
        store.computeIfPresent(key, (k, v) -> v == PLACEHOLDER ? null : v);
        // fingerprint 도 함께 제거 — release 가 호출되는 시나리오는 첫 요청이 rollback 인데,
        // 이때 다음 retry 가 다른 body 를 보내도 정상 처리되어야 함 (예: 첫 요청에서 입력 검증 실패
        // → client 가 본문을 고쳐 재전송). fingerprint 가 남아있으면 422 로 막혀 정상 흐름 깨짐.
        fingerprints.remove(key);
    }

    @Override
    public void cacheResponse(String key, int httpStatus, String body) {
        store.put(key, new CachedResponse(httpStatus, body));
    }

    @Override
    public Optional<CachedResponse> findCachedResponse(String key) {
        CachedResponse v = store.get(key);
        if (v == null || v == PLACEHOLDER) return Optional.empty();
        return Optional.of(v);
    }

    @Override
    public void recordRequestFingerprint(String key, String fingerprint) {
        // 첫 호출만 박히도록 putIfAbsent — 동시 두 호출 race 시 한 쪽이 이김. 두 번째 호출은 그대로
        // findRequestFingerprint 의 비교에서 mismatch 검출되어 422.
        fingerprints.putIfAbsent(key, fingerprint);
    }

    @Override
    public Optional<String> findRequestFingerprint(String key) {
        return Optional.ofNullable(fingerprints.get(key));
    }
}
