package com.example.billing.e2e

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * e2e IT 전용 — 매 테스트 전에 도메인 테이블 모두 비움.
 *
 * `@SpringBootTest` 가 컨텍스트를 캐시하다 보니 같은 IT 클래스 안의 테스트들이
 * Postgres 인스턴스를 공유한다. 한 테스트가 만든 invoice / outbox / payment 가 다음
 * 테스트의 단언을 오염시키는 일을 막는다.
 *
 * `shedlock / flyway_schema_history` 는 운영 메타라 건드리지 않는다.
 */
abstract class E2ECleanupSupport {

    @Autowired
    protected lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun truncateAllDomainTables() {
        // audit_entries 의 BEFORE UPDATE/DELETE 트리거는 TRUNCATE 를 막지 않는다 (트리거 적용
        // 범위 밖). 트리거를 건드릴 필요 없이 그냥 같은 TRUNCATE 로 비운다.
        jdbc.execute(
            """
            TRUNCATE TABLE
              outbox,
              dlq_replay_log,
              ledger_entries,
              refunds,
              payments,
              order_items,
              orders,
              wallets,
              invoices,
              settlement_runs,
              pricing_plans,
              aggregated_usage,
              usage_events,
              audit_entries
            RESTART IDENTITY CASCADE
            """.trimIndent(),
        )
    }
}
