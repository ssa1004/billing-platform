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
 *   <li>{@code billing:idempotency:lock:<key>} = "1" (TTL 24h) — 점유 lock</li>
 *   <li>{@code billing:idempotency:resp:<key>} = "<status>|<body>" (TTL 24h) — 응답 캐시</li>
 *   <li>{@code billing:idempotency:fp:<key>} = "<sha256-prefix-hex>" (TTL 24h) — 요청 body fingerprint
 *       (ADR-0028)</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "billing.cache.redis-enabled", havingValue = "true")
@RequiredArgsConstructor
public class RedisIdempotencyKeyStore implements IdempotencyKeyStore {

    private final StringRedisTemplate redis;

    @Value("${billing.idempotency.ttl-hours:24}")
    private long ttlHours;

    private static final String LOCK_PREFIX = "billing:idempotency:lock:";
    private static final String RESP_PREFIX = "billing:idempotency:resp:";
    private static final String FP_PREFIX = "billing:idempotency:fp:";

    @Override
    public void acquireOrThrow(String key) {
        Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_PREFIX + key, "1", Duration.ofHours(ttlHours));
        if (!Boolean.TRUE.equals(acquired)) {
            throw new DuplicateRequestException(key);
        }
    }

    @Override
    public void release(String key) {
        // 캐시된 응답은 그대로 두고 (재호출 시 같은 응답 반환), 점유 lock 만 제거.
        // fingerprint 도 함께 제거 — 첫 요청이 rollback 됐으면 다음 retry 가 다른 body 를 보내도
        // 정상 처리되어야 함 (예: 입력 검증 실패 → client 가 본문 고쳐 재전송).
        redis.delete(LOCK_PREFIX + key);
        redis.delete(FP_PREFIX + key);
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
        // 형식: "<status>|<body>". 깨진 형식 (구분자 없음 / 숫자 아닌 status) 은
        // 캐시 미스로 처리 — 운영 중 형식이 바뀌었거나 외부 변조 시 NPE / parseException 으로
        // 호출자가 죽지 않도록.
        int sep = value.indexOf('|');
        if (sep <= 0) return Optional.empty();
        int status;
        try {
            status = Integer.parseInt(value.substring(0, sep));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        String body = value.substring(sep + 1);
        return Optional.of(new CachedResponse(status, body));
    }

    @Override
    public void recordRequestFingerprint(String key, String fingerprint) {
        // setIfAbsent — 첫 호출만 박힘. 두 번째 호출은 mismatch 면 422 검출, 같으면 정상 흐름.
        redis.opsForValue().setIfAbsent(FP_PREFIX + key, fingerprint, Duration.ofHours(ttlHours));
    }

    @Override
    public Optional<String> findRequestFingerprint(String key) {
        return Optional.ofNullable(redis.opsForValue().get(FP_PREFIX + key));
    }
}
