package com.example.wallet.bootstrap.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA 스캔 경로 — wallet-adapter-out 모듈의 entity / repository 를 모두 발견.
 */
@Configuration
@EntityScan(basePackages = "com.example.wallet.adapter.out")
@EnableJpaRepositories(basePackages = "com.example.wallet.adapter.out")
public class PersistenceConfig {
}
