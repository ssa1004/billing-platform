package com.example.billing.bootstrap.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RoutingDataSource 단위 테스트 — readOnly 트랜잭션 flag 에 따른 라우팅 동작.
 *
 * <p>실제 DB 없이 두 개의 mock DataSource 를 등록하고, RoutingDataSource 가 {@code
 * TransactionSynchronizationManager} 의 readOnly 상태에 따라 어느 쪽에서 connection 을
 * 가져오는지 확인.</p>
 */
class RoutingDataSourceTest {

    private DataSource master;
    private DataSource replica;
    private Connection masterConn;
    private Connection replicaConn;
    private RoutingDataSource routing;

    @BeforeEach
    void setUp() throws Exception {
        master = mock(DataSource.class);
        replica = mock(DataSource.class);
        masterConn = mock(Connection.class);
        replicaConn = mock(Connection.class);
        when(master.getConnection()).thenReturn(masterConn);
        when(replica.getConnection()).thenReturn(replicaConn);

        Map<Object, Object> targets = new HashMap<>();
        targets.put(RoutingDataSource.Role.MASTER, master);
        targets.put(RoutingDataSource.Role.REPLICA, replica);

        routing = new RoutingDataSource();
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(master);
        routing.afterPropertiesSet();

        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void readOnlyTransaction_routesToReplica() throws Exception {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        Connection got = routing.getConnection();

        assertThat(got).isSameAs(replicaConn);
    }

    @Test
    void writeTransaction_routesToMaster() throws Exception {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

        Connection got = routing.getConnection();

        assertThat(got).isSameAs(masterConn);
    }

    @Test
    void noTransaction_routesToMaster_default() throws Exception {
        // readOnly flag 미설정 (기본 false) → master 로 라우팅. 보수적 default.
        Connection got = routing.getConnection();

        assertThat(got).isSameAs(masterConn);
    }

    @Test
    void readOnlyToggle_perCall_routesIndependently() throws Exception {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThat(routing.getConnection()).isSameAs(replicaConn);

        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        assertThat(routing.getConnection()).isSameAs(masterConn);
    }
}
