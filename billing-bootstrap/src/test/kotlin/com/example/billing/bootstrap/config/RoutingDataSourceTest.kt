package com.example.billing.bootstrap.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import javax.sql.DataSource

/**
 * RoutingDataSource 단위 테스트 — readOnly 트랜잭션 flag 에 따른 라우팅 동작.
 *
 * 실제 DB 없이 두 개의 mock DataSource 를 등록하고, RoutingDataSource 가
 * [TransactionSynchronizationManager] 의 readOnly 상태에 따라 어느 쪽에서 connection 을
 * 가져오는지 확인.
 */
class RoutingDataSourceTest {

    private lateinit var master: DataSource
    private lateinit var replica: DataSource
    private lateinit var masterConn: Connection
    private lateinit var replicaConn: Connection
    private lateinit var routing: RoutingDataSource

    @BeforeEach
    fun setUp() {
        master = mock()
        replica = mock()
        masterConn = mock()
        replicaConn = mock()
        whenever(master.connection).thenReturn(masterConn)
        whenever(replica.connection).thenReturn(replicaConn)

        val targets: MutableMap<Any, Any> = HashMap()
        targets[RoutingDataSource.Role.MASTER] = master
        targets[RoutingDataSource.Role.REPLICA] = replica

        routing = RoutingDataSource()
        routing.setTargetDataSources(targets)
        routing.setDefaultTargetDataSource(master)
        routing.afterPropertiesSet()

        TransactionSynchronizationManager.initSynchronization()
    }

    @AfterEach
    fun tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
        TransactionSynchronizationManager.setActualTransactionActive(false)
    }

    @Test
    fun readOnlyTransaction_routesToReplica() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)

        val got = routing.connection

        assertThat(got).isSameAs(replicaConn)
    }

    @Test
    fun writeTransaction_routesToMaster() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)

        val got = routing.connection

        assertThat(got).isSameAs(masterConn)
    }

    @Test
    fun noTransaction_routesToMaster_default() {
        // readOnly flag 미설정 (기본 false) → master 로 라우팅. 보수적 default.
        val got = routing.connection

        assertThat(got).isSameAs(masterConn)
    }

    @Test
    fun readOnlyToggle_perCall_routesIndependently() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)
        assertThat(routing.connection).isSameAs(replicaConn)

        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
        assertThat(routing.connection).isSameAs(masterConn)
    }
}
