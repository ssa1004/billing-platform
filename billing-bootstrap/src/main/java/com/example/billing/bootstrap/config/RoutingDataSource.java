package com.example.billing.bootstrap.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Master / Replica 라우팅 DataSource.
 *
 * <p>{@link AbstractRoutingDataSource} 를 상속해 *현재 트랜잭션의 readOnly 여부* 에 따라
 * 어느 물리 DataSource 를 쓸지 결정합니다.</p>
 *
 * <ul>
 *   <li>{@code @Transactional(readOnly = true)} → {@link Role#REPLICA} (조회 전용 slave)</li>
 *   <li>그 외 (쓰기 트랜잭션 / 트랜잭션 없음) → {@link Role#MASTER}</li>
 * </ul>
 *
 * <p><b>핵심 트릭 — LazyConnectionDataSourceProxy</b>: Spring 의
 * {@link org.springframework.transaction.PlatformTransactionManager} 는 트랜잭션을 시작할 때
 * DataSource 에서 connection 을 *즉시* 잡습니다. RoutingDataSource 가 readOnly flag 를 보려면
 * connection 획득이 *트랜잭션 동기화 setup 이후* 로 늦춰져야 합니다.
 * {@code LazyConnectionDataSourceProxy} 가 connection 획득을 첫 SQL 호출 시점까지 늦춰주는
 * Spring 진영의 표준 트릭입니다.</p>
 *
 * <p><b>왜 트랜잭션 없음 (no @Transactional) 도 master 인가</b>: replication lag (slave 가 master
 * 보다 늦음) 를 명시적으로 인지한 read 만 replica 로 보내는 정책. {@code @Transactional} 없이
 * 호출되는 read 는 caller 가 read-after-write 정합을 가정한 경우가 많아 *기본은 master*. 보수적
 * 선택. ADR-0025 참고.</p>
 *
 * <p>스레드 안전: {@link TransactionSynchronizationManager} 가 ThreadLocal 기반이라 가상 스레드
 * (Java 21 Virtual Threads) 환경에서도 동일하게 동작.</p>
 */
public class RoutingDataSource extends AbstractRoutingDataSource {

    public enum Role { MASTER, REPLICA }

    @Override
    protected Object determineCurrentLookupKey() {
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ? Role.REPLICA
                : Role.MASTER;
    }
}
