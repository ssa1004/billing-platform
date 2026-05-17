package com.example.billing.bootstrap.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy
import javax.sql.DataSource

/**
 * 운영 (prod 프로파일) 전용 — master / replica 분리 + RoutingDataSource 묶기.
 *
 * **왜 prod 만**: 로컬 / 테스트는 H2 인메모리 단일 DataSource 라 분리 의미가 없습니다.
 * dev / test 환경은 Spring Boot 기본 자동 구성을 그대로 사용 (이 config 는 비활성).
 *
 * **구성 흐름**:
 * 1. `billing.datasource.master.*` → [HikariDataSource] (write pool, 30)
 * 2. `billing.datasource.replica.*` → [HikariDataSource] (read pool, 50)
 * 3. [RoutingDataSource] 가 둘을 묶고 readOnly flag 로 라우팅
 * 4. [LazyConnectionDataSourceProxy] 로 감싸 connection 획득을 첫 SQL 까지 지연
 * 5. `@Primary` 로 등록 → JPA / Flyway 가 이 빈을 사용
 *
 * **Connection pool 분리**: master 와 replica 가 별도 HikariCP pool. 한쪽 부하가
 * 다른 쪽으로 전파되지 않습니다 (예: 무거운 dashboard 조회가 결제 트랜잭션의 connection
 * 풀을 잡지 못하게).
 *
 * ADR-0025 참고.
 */
@Configuration
@Profile("prod")
class RoutingDataSourceConfig {

    @Bean
    @ConfigurationProperties("billing.datasource.master")
    fun masterDataSource(): HikariDataSource =
        DataSourceBuilder.create().type(HikariDataSource::class.java).build()

    @Bean
    @ConfigurationProperties("billing.datasource.replica")
    fun replicaDataSource(): HikariDataSource =
        DataSourceBuilder.create().type(HikariDataSource::class.java).build()

    /**
     * 실제 JPA / Flyway 가 사용하는 DataSource. `@Primary` 로 등록.
     *
     * [LazyConnectionDataSourceProxy] 로 감싸지 않으면 트랜잭션 시작 시점에 곧바로
     * master 에서 connection 을 잡아 [RoutingDataSource.determineCurrentLookupKey]
     * 가 readOnly flag 를 못 봅니다 (트랜잭션 동기화 셋업이 connection 획득 이후라).
     */
    @Bean
    @Primary
    fun dataSource(masterDataSource: HikariDataSource, replicaDataSource: HikariDataSource): DataSource {
        val routing = RoutingDataSource()
        routing.setTargetDataSources(
            mapOf(
                RoutingDataSource.Role.MASTER to masterDataSource,
                RoutingDataSource.Role.REPLICA to replicaDataSource,
            ),
        )
        routing.setDefaultTargetDataSource(masterDataSource) // readOnly flag 를 못 읽으면 master.
        routing.afterPropertiesSet()
        return LazyConnectionDataSourceProxy(routing)
    }
}
