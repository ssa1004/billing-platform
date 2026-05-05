package com.example.wallet.bootstrap.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.example.wallet.bootstrap.config.properties.WalletProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 캐시 설정 — Caffeine 인프로세스 캐시.
 *
 * <p>운영의 2단계 캐시(Caffeine L1 + Redis L2) 는 ADR-0011 에 정리된 대로 별도 RedisCacheConfig 에서
 * 활성화한다 ({@code wallet.cache.redis-enabled=true} 일 때).</p>
 */
@Configuration
@EnableCaching
@ConditionalOnProperty(name = "wallet.cache.redis-enabled", havingValue = "false", matchIfMissing = true)
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(WalletProperties props) {
        var manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(props.cache().localTtlSeconds(), TimeUnit.SECONDS)
                .maximumSize(10_000));
        return manager;
    }
}
