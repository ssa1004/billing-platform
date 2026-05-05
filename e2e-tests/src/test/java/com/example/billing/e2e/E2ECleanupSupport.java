package com.example.billing.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * e2e IT 전용 — 매 테스트 전에 도메인 테이블 모두 비움.
 *
 * <p>{@code @SpringBootTest} 가 컨텍스트를 캐시하다 보니 같은 IT 클래스 안의 테스트들이
 * Postgres 인스턴스를 공유한다. 한 테스트가 만든 invoice / outbox / payment 가 다음
 * 테스트의 단언을 오염시키는 일을 막는다.</p>
 *
 * <p>{@code shedlock / flyway_schema_history} 는 운영 메타라 건드리지 않는다.</p>
 */
abstract class E2ECleanupSupport {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void truncateAllDomainTables() {
        jdbc.execute("""
                TRUNCATE TABLE
                  outbox,
                  idempotency_keys,
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
                  usage_events
                RESTART IDENTITY CASCADE
                """);
    }
}
