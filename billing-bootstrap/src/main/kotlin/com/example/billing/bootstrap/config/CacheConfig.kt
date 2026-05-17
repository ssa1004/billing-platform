package com.example.billing.bootstrap.config

import com.example.billing.bootstrap.config.properties.BillingProperties
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

/**
 * 캐시 설정 — Caffeine 인프로세스 캐시.
 *
 * 로컬/dev 프로필에서는 Caffeine 을 사용하고, `billing.cache.redis-enabled=true` 인 운영
 * 프로필에서는 Spring Boot 의 Redis CacheManager 자동 설정에 맡긴다.
 */
@Configuration
@EnableCaching
@ConditionalOnProperty(name = ["billing.cache.redis-enabled"], havingValue = "false", matchIfMissing = true)
class CacheConfig {

    @Bean
    fun cacheManager(props: BillingProperties): CacheManager {
        val manager = CaffeineCacheManager()
        manager.setCaffeine(
            Caffeine.newBuilder()
                .expireAfterWrite(props.cache.localTtlSeconds, TimeUnit.SECONDS)
                .maximumSize(10_000),
        )
        return manager
    }
}
