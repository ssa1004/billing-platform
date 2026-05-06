# Architecture Decision Records

각 결정의 *배경 → 결정 → 장단점* 을 짧게 기록 ([Michael Nygard 형식](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions) 참고).

| # | 제목 | 상태 |
|---|---|---|
| [0001](0001-modular-monolith.md) | Spring Modulith 기반 모듈러 모놀리스 (MSA 대신) | 적용 |
| [0002](0002-hexagonal-architecture.md) | 헥사고날 아키텍처, package-by-feature | 적용 |
| [0003](0003-ddd-aggregate-boundaries.md) | 애그리거트 경계 — Wallet / Order / Payment 분리 | 적용 |
| [0004](0004-cqrs.md) | CQRS — JPA 쓰기, 분리가 필요한 곳만 별도 읽기 | 적용 |
| [0005](0005-outbox-and-dlq.md) | Outbox + Kafka DLQ로 이벤트 일관성 보장 | 적용 |
| [0006](0006-idempotency-key.md) | 멱등성 키 — Redis NX 기반 | 적용 |
| [0007](0007-locking-strategy.md) | 락 전략 — Wallet은 낙관적, race가 잦은 곳은 비관적 | 적용 |
| [0008](0008-resilience4j.md) | Resilience4j Circuit Breaker for PG | 적용 |
| [0009](0009-virtual-threads.md) | Java 21 Virtual Threads | 적용 |
| [0010](0010-spring-batch-reconciliation.md) | Spring Batch chunk + skip + retry 정산 | 적용 |
| [0011](0011-two-layer-cache.md) | Caffeine L1 + Redis L2 두 단계 캐시 | 적용 |
| [0012](0012-wiremock-pg-contract.md) | Wiremock으로 PG contract 테스트 | 적용 |
| [0013](0013-settlement-advisory-lock.md) | 월별 정산 동시성 — Postgres advisory lock | 적용 |
| [0014](0014-skip-locked-worker-pool.md) | Worker pool 병렬 처리 — `FOR UPDATE SKIP LOCKED` | 적용 |
| [0015](0015-pricing-snapshot.md) | 청구서의 가격 정책 snapshot | 적용 |
| [0016](0016-batch-scheduling-shedlock.md) | Batch Job 스케줄링 — `@Scheduled` + ShedLock | 적용 |
| [0017](0017-multi-tenancy-row-level.md) | 멀티테넌시 — Row-Level Isolation (`customer_id`) | 적용 |
| [0018](0018-credit-separate-from-wallet.md) | Credit (선불/프로모 잔액) 을 Wallet 과 분리 | 적용 |
| [0019](0019-credit-invoice-application-flow.md) | Credit ↔ Invoice 적용 흐름 + 만료 batch | 적용 |
| [0020](0020-usage-forecast.md) | 월말 사용량/비용 예상 (UsageForecast) | 적용 |
| [0021](0021-budget-alert-rule.md) | BudgetAlertRule — 임계 초과 시 알림 | 적용 |
