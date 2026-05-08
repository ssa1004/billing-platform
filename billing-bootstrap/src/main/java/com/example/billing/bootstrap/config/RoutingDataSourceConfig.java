package com.example.billing.bootstrap.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 운영 (prod 프로파일) 전용 — master / replica 분리 + RoutingDataSource 묶기.
 *
 * <p><b>왜 prod 만</b>: 로컬 / 테스트는 H2 인메모리 단일 DataSource 라 분리 의미가 없습니다.
 * dev / test 환경은 Spring Boot 기본 자동 구성을 그대로 사용 (이 config 는 비활성).</p>
 *
 * <p><b>구성 흐름</b>:</p>
 * <ol>
 *   <li>{@code billing.datasource.master.*} → {@link HikariDataSource} (write pool, 30)</li>
 *   <li>{@code billing.datasource.replica.*} → {@link HikariDataSource} (read pool, 50)</li>
 *   <li>{@link RoutingDataSource} 가 둘을 묶고 readOnly flag 로 라우팅</li>
 *   <li>{@link LazyConnectionDataSourceProxy} 로 감싸 connection 획득을 첫 SQL 까지 지연</li>
 *   <li>{@code @Primary} 로 등록 → JPA / Flyway 가 이 빈을 사용</li>
 * </ol>
 *
 * <p><b>Connection pool 분리</b>: master 와 replica 가 별도 HikariCP pool. 한쪽 부하가
 * 다른 쪽으로 전파되지 않습니다 (예: 무거운 dashboard 조회가 결제 트랜잭션의 connection
 * 풀을 잡지 못하게).</p>
 *
 * <p>ADR-0025 참고.</p>
 */
@Configuration
@Profile("prod")
public class RoutingDataSourceConfig {

    @Bean
    @ConfigurationProperties("billing.datasource.master")
    public HikariDataSource masterDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties("billing.datasource.replica")
    public HikariDataSource replicaDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    /**
     * 실제 JPA / Flyway 가 사용하는 DataSource. {@code @Primary} 로 등록.
     *
     * <p>{@link LazyConnectionDataSourceProxy} 로 감싸지 않으면 트랜잭션 시작 시점에 곧바로
     * master 에서 connection 을 잡아 {@link RoutingDataSource} 의 {@code determineCurrentLookupKey()}
     * 가 readOnly flag 를 못 봅니다 (트랜잭션 동기화 셋업이 connection 획득 이후라).</p>
     */
    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource masterDataSource, HikariDataSource replicaDataSource) {
        RoutingDataSource routing = new RoutingDataSource();
        routing.setTargetDataSources(Map.of(
                RoutingDataSource.Role.MASTER, masterDataSource,
                RoutingDataSource.Role.REPLICA, replicaDataSource
        ));
        routing.setDefaultTargetDataSource(masterDataSource); // readOnly flag 를 못 읽으면 master.
        routing.afterPropertiesSet();
        return new LazyConnectionDataSourceProxy(routing);
    }
}
