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
 * 캐시 설정.
 *
 * <ul>
 *   <li>로컬/dev: Caffeine in-process L1 only (wallet.cache.redis-enabled=false)</li>
 *   <li>운영: Caffeine L1 + Redis L2 — 별도 RedisCacheConfig 가 활성 (TODO 향후 추가)</li>
 * </ul>
 *
 * 현재는 단순화 — Caffeine 만 등록. 2-tier 는 ADR-0011 의 향후 보강 항목.
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
