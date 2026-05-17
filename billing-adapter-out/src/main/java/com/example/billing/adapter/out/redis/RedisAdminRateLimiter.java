package com.example.billing.adapter.out.redis;

import com.example.billing.application.port.out.AdminRateLimiter;
import com.example.billing.domain.shared.RateLimitDecision;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * admin endpoint 의 IP × scope 별 token bucket. INCR + PEXPIRE Lua 한 번에 (atomic).
 *
 * <p>키 구조: {@code billing:admin:rl:<scope>:<callerKey>}. scope =
 * {@code admin:dlq:read|write|bulk}. callerKey = 호출자 IP.
 *
 * <p>한도는 분당 60 (window 60s) — 사람이 손으로 누르는 빈도 가정. bulk 같은 무거운 작업은
 * 별도 scope 로 더 낮게 잡을 수 있도록 분리 (다음 ADR 에서 scope 별 한도 분리 검토). 현재는
 * 통일된 분당 60.
 *
 * <p>{@code billing.cache.redis-enabled=true} 일 때만 활성 — dev / 테스트는 fallback (no-op)
 * adapter 가 빈 등록 (어댑터 모듈 하단의 {@code @ConditionalOnMissingBean} 으로 처리).
 *
 * <p>notification-hub 의 같은 이름 어댑터 (ADR-0015) 와 같은 Lua / 키 구조 패턴 — 두 서비스가
 * 같은 Redis 를 쓰더라도 namespace 가 분리됨 ({@code billing:} vs {@code notif:}).
 */
@Component
@ConditionalOnProperty(name = "billing.cache.redis-enabled", havingValue = "true")
@RequiredArgsConstructor
public class RedisAdminRateLimiter implements AdminRateLimiter {

    static final String NAMESPACE = "billing:admin:rl:";

    private static final String LUA_INCR_AND_TTL =
            """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('PTTL', KEYS[1])
            return {current, ttl}
            """;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SCRIPT =
            new DefaultRedisScript<>(LUA_INCR_AND_TTL, List.class);

    private final StringRedisTemplate redis;

    @Value("${billing.admin.rate-limit.window-ms:60000}")
    private long windowMs;

    @Value("${billing.admin.rate-limit.per-window:60}")
    private long limit;

    @Override
    public RateLimitDecision tryConsume(String scope, String callerKey) {
        String key = NAMESPACE + scope + ":" + callerKey;
        @SuppressWarnings("unchecked")
        List<Long> result =
                redis.execute(SCRIPT, Collections.singletonList(key), String.valueOf(windowMs));
        long current = result.get(0);
        long ttl = result.get(1);
        if (current > limit) {
            return RateLimitDecision.deny(ttl < 0 ? windowMs : ttl);
        }
        return RateLimitDecision.allow(Math.max(0, limit - current));
    }
}
