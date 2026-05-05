package com.example.billing.adapter.out.persistence.idempotency;

import com.example.billing.application.port.out.IdempotencyKeyStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory 멱등성 키 저장소 — 로컬 dev 전용. {@code wallet.cache.redis-enabled=false} 일 때 활성.
 * 운영은 {@link RedisIdempotencyKeyStore} (Redis NX SETNX).
 */
@Component
@ConditionalOnProperty(name = "wallet.cache.redis-enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryIdempotencyKeyStore implements IdempotencyKeyStore {

    private final ConcurrentMap<String, CachedResponse> store = new ConcurrentHashMap<>();
    private static final CachedResponse PLACEHOLDER = new CachedResponse(0, "");

    @Override
    public void acquireOrThrow(String key) {
        if (store.putIfAbsent(key, PLACEHOLDER) != null) {
            throw new DuplicateRequestException(key);
        }
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
}
