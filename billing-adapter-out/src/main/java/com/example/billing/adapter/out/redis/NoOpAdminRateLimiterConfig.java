package com.example.billing.adapter.out.redis;

import com.example.billing.application.port.out.AdminRateLimiter;
import com.example.billing.domain.shared.RateLimitDecision;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

/**
 * Redis 가 비활성인 dev / test 환경의 fallback {@link AdminRateLimiter} — 항상 허용.
 *
 * <p>{@link RedisAdminRateLimiter} 가 등록되지 않은 경우에만 활성 ({@code @ConditionalOnMissingBean}).
 * 운영에서는 Redis 가 켜져 있어야 한다 — 이 fallback 으로 떨어지면 admin endpoint 의 보호가
 * 사라지므로 boot 로그에 명시.
 */
@Configuration
public class NoOpAdminRateLimiterConfig {

    @Bean
    @ConditionalOnMissingBean(AdminRateLimiter.class)
    public AdminRateLimiter adminRateLimiter() {
        return new NoOp();
    }

    static final class NoOp implements AdminRateLimiter {

        @Override
        public RateLimitDecision tryConsume(String scope, String callerKey) {
            return RateLimitDecision.allow(Long.MAX_VALUE);
        }
    }
}
