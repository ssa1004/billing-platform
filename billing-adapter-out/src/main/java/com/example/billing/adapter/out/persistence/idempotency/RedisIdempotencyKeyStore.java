package com.example.billing.adapter.out.persistence.idempotency;

import com.example.billing.application.port.out.IdempotencyKeyStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 기반 멱등성 키 저장소 — SET NX EX 한 번에 (atomic).
 *
 * <p>키 구조:</p>
 * <ul>
 *   <li>{@code wallet:idempotency:lock:<key>} = "1" (TTL 24h) — 점유 lock</li>
 *   <li>{@code wallet:idempotency:resp:<key>} = "<status>|<body>" (TTL 24h) — 응답 캐시</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "wallet.cache.redis-enabled", havingValue = "true")
@RequiredArgsConstructor
public class RedisIdempotencyKeyStore implements IdempotencyKeyStore {

    private final StringRedisTemplate redis;

    @Value("${wallet.idempotency.ttl-hours:24}")
    private long ttlHours;

    private static final String LOCK_PREFIX = "wallet:idempotency:lock:";
    private static final String RESP_PREFIX = "wallet:idempotency:resp:";

    @Override
    public void acquireOrThrow(String key) {
        Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_PREFIX + key, "1", Duration.ofHours(ttlHours));
        if (!Boolean.TRUE.equals(acquired)) {
            throw new DuplicateRequestException(key);
        }
    }

    @Override
    public void cacheResponse(String key, int httpStatus, String body) {
        String value = httpStatus + "|" + (body != null ? body : "");
        redis.opsForValue().set(RESP_PREFIX + key, value, Duration.ofHours(ttlHours));
    }

    @Override
    public Optional<CachedResponse> findCachedResponse(String key) {
        String value = redis.opsForValue().get(RESP_PREFIX + key);
        if (value == null) return Optional.empty();
        int sep = value.indexOf('|');
        int status = Integer.parseInt(value.substring(0, sep));
        String body = value.substring(sep + 1);
        return Optional.of(new CachedResponse(status, body));
    }
}
