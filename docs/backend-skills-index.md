# 백엔드 스킬 인덱스 — 이 레포에서 무엇을 배우나

> 이 레포(B2B SaaS 결제/청구/정산 백엔드)가 시연하는 백엔드 / 운영 패턴을
> **"무엇 → 이 레포 어디서 → 왜(ADR) → 더 깊은 이론"** 으로 잇는 학습용 인덱스.
> "이 패턴 공부하려면 어디부터 보나"의 진입점. 설명을 다시 쓰지 않고 코드·결정·이론으로 연결만 한다.
> 결정 배경은 [docs/adr/](adr/) 의 ADR 33건에 정리되어 있다.

## 돈 · 멱등성 · 정확히 한 번

| 패턴 | 이 레포 어디서 | 왜 (ADR) | 한 줄 |
|------|---------------|---------|-------|
| **Idempotency-Key (Redis NX + DB unique)** | `adapter/web/filter`, `adapter/out/persistence/idempotency` | [ADR-0006](adr/0006-idempotency-key.md) | 결제 재시도가 와도 한 번만 — Redis SETNX 1차, DB unique 가 최후 방어선 |
| **Idempotency 응답 캐싱 (24h replay)** | `IdempotencyResponseCacheFilter` (`adapter/web/filter`) | [ADR-0024](adr/0024-idempotency-response-cache.md) | 같은 키 재시도에 첫 응답(status+body)을 그대로 반환 — Stripe 명세. timeout 후 "결제됐나?" 정합 사고 차단 |
| **본문 fingerprint 검증 (mismatch → 422)** | 같은 filter + idempotency store | [ADR-0028](adr/0028-idempotency-body-fingerprint.md) | 같은 키인데 body 가 다르면 client bug — SHA-256 prefix 16B 비교로 즉시 422 |
| **Outbox 패턴** | `adapter/out/persistence/outbox` (OutboxRelay) | [ADR-0005](adr/0005-outbox-and-dlq.md) | 도메인 트랜잭션 안에서 outbox INSERT → 별도 relay 가 Kafka 발행. dual-write 문제 해소 |
| **append-only Ledger** | `domain/ledger` | [ADR-0003](adr/0003-ddd-aggregate-boundaries.md), [ADR-0030](adr/0030-soft-delete-billing-rows.md) | 원장은 수정/삭제 없이 추가만 — 회계 정합성. 결제/회계 row 는 soft delete |

→ 이론: `dev-lab/distributed-systems` (exactly-once 환상 / 멱등성 / money), `dev-lab/cdc` (Outbox vs CDC), `dev-lab/kafka`

## 동시성 · 락 (Postgres)

| 패턴 | 이 레포 어디서 | 왜 (ADR) | 한 줄 |
|------|---------------|---------|-------|
| **낙관적 락 (`@Version`)** | `domain/wallet`, `domain/credit` | [ADR-0007](adr/0007-locking-strategy.md) | 충돌이 드문 Wallet 잔액 차감 — lost update 를 version 으로 막고 충돌 시 재시도 |
| **advisory lock (`pg_advisory_xact_lock`)** | `adapter/out/lock` (AdvisoryLock) | [ADR-0013](adr/0013-settlement-advisory-lock.md) | 같은 customer × period 정산을 직렬화 — row 없이 이름 기반 잠금. 트랜잭션 종료 시 자동 해제 |
| **`FOR UPDATE SKIP LOCKED` worker pool** | SettlementRun / Invoice claim 쿼리 (`adapter/out/persistence/jpa/repository`) | [ADR-0014](adr/0014-skip-locked-worker-pool.md) | 여러 worker 가 잠긴 row 는 건너뛰고 자유로운 것만 — DB 가 사전 분할 없이 분배 (JPA `lock.timeout=-2`) |

→ 이론: `dev-lab/postgresql` (advisory lock / SKIP LOCKED / MVCC / 잠금), `dev-lab/distributed-systems` (분산 락)

## 회복탄력성 (Resilience)

| 패턴 | 이 레포 어디서 | 왜 (ADR) | 한 줄 |
|------|---------------|---------|-------|
| **Circuit Breaker + Retry (PG)** | `RestClientPgClient` (`adapter/out/pg`) | [ADR-0008](adr/0008-resilience4j.md) | PG 장애가 우리 트랜잭션으로 전파되지 않게 차단 + 지수 백오프 재시도. CB OPEN 시 즉시 fallback |
| **ThreadPool Bulkhead 격리** | `BulkheadedPgClient` (`adapter/out/pg`) | [ADR-0026](adr/0026-bulkhead-thread-pool-isolation.md) | PG/webhook/audit-export 도메인별 worker pool — 한쪽 슬로우다운이 다른 endpoint 로 cascade 안 됨. full 시 503 + Retry-After |
| **Bulkhead worker 까지 MDC 전파** | `MdcContextPropagator` (`adapter/out/pg`) | [ADR-0027](adr/0027-bulkhead-mdc-context-propagation.md) | 별도 worker thread 로 넘어가도 traceId/requestId 가 로그에 이어짐 |
| **Kafka DLQ** | `adapter/out/dlq`, `adapter/out/messaging` | [ADR-0005](adr/0005-outbox-and-dlq.md) | 컨슈머 N회 실패 → `.DLT` topic 격리. 정상 컨슈머 처리가 막히지 않음 |

→ 이론: `dev-lab/resilience` (circuit breaker / bulkhead / 격벽), `dev-lab/networking` (커넥션 풀 / timeout), `dev-lab/kafka` (DLQ)

## 처리량 · 데이터 경로

| 패턴 | 이 레포 어디서 | 왜 (ADR) | 한 줄 |
|------|---------------|---------|-------|
| **Java 21 Virtual Threads** | `application.yml` (`spring.threads.virtual.enabled`) | [ADR-0009](adr/0009-virtual-threads.md) | blocking I/O fan-out(DB+PG+Outbox+Kafka)을 동기 코드 그대로 유지하며 처리량 ↑. WebFlux 학습곡선 회피 |
| **Read-Replica 라우팅** | `RoutingDataSource` (`adapter/out/persistence`) | [ADR-0025](adr/0025-read-replica-routing.md) | `@Transactional(readOnly=true)` 한 줄로 replica 라우팅 — dashboard/SIEM read 가 결제 OLTP pool 을 못 잡게. `LazyConnectionDataSourceProxy` 트릭 |
| **조회 캐시 (Caffeine ↔ Redis)** | `@Cacheable("wallets")` | [ADR-0011](adr/0011-two-layer-cache.md) | hot path `GET /wallet` — dev 는 Caffeine, prod 는 Redis 공유 (프로필 분기) |
| **Hibernate query plan cache 튜닝** | `application.yml` + PG prepared | [ADR-0032](adr/0032-hibernate-query-plan-cache-tuning.md) | plan cache miss / PG prepared statement 비용 절감 |

→ 이론: `dev-lab/jvm` (Virtual Threads / Loom), `dev-lab/postgresql` (replica / prepared / MVCC), `dev-lab/performance` (hot path / 캐시), `dev-lab/system-design` (CQRS read 분리)

## Spring Batch · 스케줄링

| 패턴 | 이 레포 어디서 | 왜 (ADR) | 한 줄 |
|------|---------------|---------|-------|
| **chunk + skip + retry 정산/정합성** | `billing-batch/batch` (Reconciliation / MonthlySettlement Job) | [ADR-0010](adr/0010-spring-batch-reconciliation.md) | 수십만 wallet 을 `JpaPagingItemReader` 로 메모리 안전 처리, 일부 실패는 skip 하고 전체는 진행 |
| **`@Scheduled` + ShedLock** | `BillingJobScheduler` (`billing-batch/batch/scheduler`) | [ADR-0016](adr/0016-batch-scheduling-shedlock.md) | replica > 1 에서도 batch 가 정확히 한 인스턴스만 실행. `usingDbTime()` 으로 시계 drift 회피 |

→ 이론: `dev-lab/distributed-systems` (분산 스케줄 / 단일 실행 보장), `dev-lab/system-design` (batch vs streaming)

## 멀티테넌시 · 도메인 모델링

| 패턴 | 이 레포 어디서 | 왜 (ADR) | 한 줄 |
|------|---------------|---------|-------|
| **Row-level 멀티테넌시 (`customer_id`)** | 전 repository 가 `customerId` 필터 강제 | [ADR-0017](adr/0017-multi-tenancy-row-level.md) | tenant 수 무한 확장 + 운영 단순. application 레벨 격리(누락 위험은 코드리뷰로) — RLS 는 향후 |
| **PricingPlan snapshot** | `domain/pricing` (PricingSnapshot in Invoice) | [ADR-0015](adr/0015-pricing-snapshot.md) | 요금제가 바뀌어도 과거 invoice 금액 불변 — invoice 가 그 시점 요금표를 품음 (회계/감사) |
| **Credit 를 Wallet 과 분리** | `domain/credit` | [ADR-0018](adr/0018-credit-separate-from-wallet.md), [ADR-0019](adr/0019-credit-invoice-application-flow.md) | 발급/만료/회수 라이프사이클 + 환불 불가 — Wallet(현금성)과 다른 애그리거트. invoice 적용 + 만료 batch |
| **헥사고날 + Spring Modulith**(= 핵심 로직을 가운데 두고 바깥(DB·웹)은 콘센트·플러그로만 잇는 헥사고날 구조에, 모듈 간 의존 방향이 어긋나면 빌드가 깨지게 검증해 주는 Spring 도구를 얹은 것) | 6개 gradle 모듈 (in/app/domain/out/batch/bootstrap) | [ADR-0001](adr/0001-modular-monolith.md), [ADR-0002](adr/0002-hexagonal-architecture.md), [ADR-0004](adr/0004-cqrs.md) | 모듈 의존 방향을 빌드 시점에 검증. MSA 대신 모듈러 모놀리스 + 부분 CQRS |

→ 이론: `dev-lab/system-design` (헥사고날 / 모듈 경계 / 멀티테넌시 / DDD 애그리거트), `dev-lab/postgresql` (인덱스 / 격리 모델)

## 운영 / SRE

| 패턴 | 이 레포 어디서 | 왜 (ADR) | 한 줄 |
|------|---------------|---------|-------|
| **DLQ 관리 콘솔 API (v2)** | `adapter/web` (AdminDlqController) + `adapter/out/dlq` | [ADR-0033](adr/0033-dlq-admin-api.md) | filter/detail/bulk/stats 8 endpoint. bulk-replay 는 `confirm=true` 없으면 dry-run 강제 — 재청구 사고 방지 + Idempotency-Key 복사로 이중 결제 차단 |
| **Webhook 전송 (HMAC + retry + replay)** | `domain/webhook`, `adapter/out/webhook` | [ADR-0022](adr/0022-webhook-delivery.md), [ADR-0029](adr/0029-webhook-secret-rotation-grace.md) | 고객사 webhook 서명/재시도/재발송 + secret rotation grace window(24h) |
| **Audit Log (append-only)** | `domain/audit`, `adapter/out` | [ADR-0023](adr/0023-audit-log.md) | who/when/what 감사 로그 — DLQ replay 등 운영 행위가 같은 테이블에 박혀 분쟁 시 즉답 |
| **API 버전 routing (path-based v1/v2)** | `adapter/web/v2`, `adapter/web/dto/v2` | [ADR-0031](adr/0031-api-versioning-path-based.md) | `/api/v1` ↔ `/api/v2` path 분기로 호환 유지 |
| **Wiremock PG contract** | `infrastructure/wiremock` | [ADR-0012](adr/0012-wiremock-pg-contract.md) | 외부 PG 를 stub 으로 흉내 — 계약 테스트 |

→ 이론: `dev-lab/observability` (감사 로그 / SIEM export / 3축), `dev-lab/resilience` (DLQ replay / webhook retry), `dev-lab/incident-response` (운영 콘솔 / 안전망)

## 학습 순서 제안 (이 레포 기준)

1. **README 상단 + 흐름 다이어그램** → 사용량→청구→정산 / 실시간 결제 두 흐름 감 잡기
2. **[docs/adr/](adr/)** → 왜 그렇게 했나 (ADR 33건) ← 이 레포의 핵심 학습 자료. 특히 빌링 특화는 ADR-0013(advisory lock) / 0014(SKIP LOCKED) / 0015(pricing snapshot) / 0024+0028(idempotency) / 0026(bulkhead) / 0033(DLQ admin)
3. **위 패턴 표** 에서 관심 패턴 → 코드 위치 + 해당 ADR + dev-lab 이론
4. **돈 직결 안전망** (Idempotency 24h 캐시 + body fingerprint + DLQ dry-run) → 결제 도메인 특유의 정합성 방어선이 어떻게 겹쳐 있는지
5. **`load/`** → k6 시나리오가 가드하는 invariant (Idempotency hit ratio / advisory lock 대기 / multi-currency 분리)

> 짝 학습 레포: [dev-lab](https://github.com/ssa1004/dev-lab) (이론) ↔ 이 레포 (구현). 이론에서 "왜"를, 여기서 "실제로 어떻게"를 본다.
> 결제/정산 도메인은 특히 `dev-lab/postgresql`(락·MVCC) · `dev-lab/distributed-systems`(멱등성·exactly-once) · `dev-lab/resilience`(CB·bulkhead) · `dev-lab/cdc`(Outbox) 와 짝으로 보면 좋다.
