package com.example.billing.adapter.out.redis

import com.example.billing.application.port.out.AdminRateLimiter
import com.example.billing.domain.shared.RateLimitDecision
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import kotlin.math.max

/**
 * admin endpoint 의 IP × scope 별 token bucket. INCR + PEXPIRE Lua 한 번에 (atomic).
 *
 * 키 구조: `billing:admin:rl:<scope>:<callerKey>`. scope =
 * `admin:dlq:read|write|bulk`. callerKey = 호출자 IP.
 *
 * 한도는 분당 60 (window 60s) — 사람이 손으로 누르는 빈도 가정. bulk 같은 무거운 작업은
 * 별도 scope 로 더 낮게 잡을 수 있도록 분리 (다음 ADR 에서 scope 별 한도 분리 검토). 현재는
 * 통일된 분당 60.
 *
 * `billing.cache.redis-enabled=true` 일 때만 활성 — dev / 테스트는 fallback (no-op)
 * adapter 가 빈 등록 (어댑터 모듈 하단의 `@ConditionalOnMissingBean` 으로 처리).
 *
 * notification-hub 의 같은 이름 어댑터 (ADR-0015) 와 같은 Lua / 키 구조 패턴 — 두 서비스가
 * 같은 Redis 를 쓰더라도 namespace 가 분리됨 (`billing:` vs `notif:`).
 */
@Component
@ConditionalOnProperty(name = ["billing.cache.redis-enabled"], havingValue = "true")
class RedisAdminRateLimiter(
    private val redis: StringRedisTemplate,
) : AdminRateLimiter {

    @Value("\${billing.admin.rate-limit.window-ms:60000}")
    private var windowMs: Long = 60_000

    @Value("\${billing.admin.rate-limit.per-window:60}")
    private var limit: Long = 60

    override fun tryConsume(scope: String, callerKey: String): RateLimitDecision {
        val key = NAMESPACE + scope + ":" + callerKey
        @Suppress("UNCHECKED_CAST")
        val result = redis.execute(SCRIPT, listOf(key), windowMs.toString()) as List<Long>
        val current = result[0]
        val ttl = result[1]
        if (current > limit) {
            return RateLimitDecision.deny(if (ttl < 0) windowMs else ttl)
        }
        return RateLimitDecision.allow(max(0, limit - current))
    }

    companion object {
        const val NAMESPACE = "billing:admin:rl:"

        private const val LUA_INCR_AND_TTL = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('PTTL', KEYS[1])
            return {current, ttl}
            """

        @Suppress("UNCHECKED_CAST")
        private val SCRIPT: DefaultRedisScript<List<*>> =
            DefaultRedisScript(LUA_INCR_AND_TTL, List::class.java)
    }
}
