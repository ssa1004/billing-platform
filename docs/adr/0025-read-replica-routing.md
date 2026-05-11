# ADR-0025: Read-Replica 라우팅 (AbstractRoutingDataSource)

## 상태
적용

## 배경

billing 시스템의 읽기 부하는 쓰기 부하보다 한 자릿수 이상 큼. 청구서 조회, dashboard,
SIEM export, audit timeline 조회 같은 무거운 query 가 master 한 곳을 같이 쓰면 OLTP (결제
/ 환불 / 크레딧 적용) 트랜잭션이 같은 connection pool 을 두고 경쟁합니다.

### 실제 시나리오

- 운영자 dashboard 가 모든 customer 의 최근 24h audit timeline 을 조회 (수만 row scan).
- 같은 시점에 결제 트래픽이 평소처럼 들어옴 — connection 이 dashboard 에 잡혀 결제 트랜잭션이
  pool wait 에 걸림 → 결제 latency 가 P99 에서 초 단위로 튐.
- 회계 리포트 batch 가 월말에 invoice 수개월치 를 한꺼번에 SELECT — 같은 효과.

읽기 부하가 큰 OLTP 시스템의 표준 처방은 master / replica 분리 — write 는 master, read 는
replica. Spring 진영의 표준 패턴은 `AbstractRoutingDataSource` 로 `@Transactional(readOnly =
true)` 를 hint 삼아 자동 라우팅하는 방식.

## 결정

### 흐름

```
Application
   │
   ▼
@Primary DataSource (LazyConnectionDataSourceProxy)
   │
   ▼
RoutingDataSource (extends AbstractRoutingDataSource)
   │
   ├─ readOnly=true → Replica HikariDataSource (read-pool, 50)
   └─ readOnly=false → Master HikariDataSource (write-pool, 30)
```

**라우팅 키**: `TransactionSynchronizationManager.isCurrentTransactionReadOnly()`. 즉:

- `@Transactional(readOnly = true)` 메서드 → replica
- `@Transactional` (write) 메서드 → master
- `@Transactional` 없는 read → master (보수적 default)

### LazyConnectionDataSourceProxy 의 역할

Spring 의 `PlatformTransactionManager` 는 트랜잭션 시작 시점에 DataSource 에서 connection 을
즉시 잡습니다. `RoutingDataSource` 가 readOnly flag 를 보려면 connection 획득이 트랜잭션
동기화 셋업 이후로 늦춰져야 합니다.

`LazyConnectionDataSourceProxy` 는 connection 획득을 첫 SQL 호출 시점까지 지연시킵니다.
이 시점엔 readOnly flag 가 이미 set 되어 있어 routing 이 정상 동작.

이 트릭 없이 RoutingDataSource 만 쓰면 모든 트랜잭션이 master 로 가는 흔한 함정이 있고
(Spring 진영 cookbook 에서 공통적으로 지적), 본 구현은 표준 패턴을 그대로 따릅니다.

### 환경별 전략

```
prod   → master + replica 분리 (RoutingDataSourceConfig 활성)
dev    → H2 인메모리 단일 DataSource (Spring Boot 자동 구성)
test   → 테스트 컨테이너 / H2 단일 DataSource
```

dev / test 가 routing 을 따라가면 환경 인프라 의존성이 늘어나니 배제. `@Profile("prod")` 로
config 자체가 prod 에서만 활성. dev / test 는 기존과 동일.

### Replica URL 결정

```yaml
billing.datasource.replica.url:
  jdbc:postgresql://${DB_REPLICA_HOST:${DB_HOST:postgres}}:${DB_REPLICA_PORT:${DB_PORT:5432}}/${DB_NAME:billing}
```

`DB_REPLICA_HOST` 가 미설정이면 master 와 같은 호스트. replica 가 인프라 단에 아직 없을
수도 있는 환경 (k8s 클러스터 단계별 적용) 에서 같은 master 로 fallback. 라우팅 코드는
그대로 동작하지만 실질 효과는 master 한 곳. 인프라 준비되면 환경변수만 바꾸면 됨.

### Connection pool 분리

| | master | replica |
|---|---|---|
| pool size | 30 | 50 |
| read-only | false | true (JDBC level) |
| 용도 | write tx + write 직후 read | 모든 readOnly tx |

read pool 이 두드러지게 큰 이유는 dashboard / SIEM 류 무거운 read 가 replica 에 집중되도록
분배. master pool 은 OLTP 결제 트랜잭션을 위해 보호.

### Replication lag 처리

Postgres streaming replication 은 보통 < 100ms 이지만 초 단위까지 늦어질 수 있음. write 직후
같은 row 를 read 하는 흐름에서 lag 가 보이면 stale data 를 읽음.

대응:
1. **기본 정책**: write 는 같은 트랜잭션 내에서 read 도 함께. `@Transactional(readOnly=false)`
   안에서 select / insert / select 는 모두 master.
2. **별도 read 흐름**: query service 가 `@Transactional(readOnly=true)` 명시. read-after-write
   요구가 아닌 경우 (대부분의 dashboard / list 조회) 가 여기 해당.
3. **read-after-write 강제 옵션**: 필요한 경우 `@Transactional` (write) 안에서 read — master
   강제. 또는 향후 `@MasterRequired` aspect 도입 검토 (후속).

`@Transactional` 없는 read 는 보수적으로 master 로 보내는 정책 (`determineCurrentLookupKey`
의 default). 호출자가 lag 영향을 인지하지 않은 read 는 안전한 master 쪽으로.

### 적용 대상 (현재 readOnly 명시)

- `WalletQueryService`
- `AuditQueryService`
- `AgedReceivablesService`
- `BudgetAlertHistoryQueryService`
- `CustomerCreditQueryService`
- `UsageForecastService`

이들이 자동으로 replica 로 라우팅됨. 별도 코드 변경 없음 — annotation 한 줄로 라우팅이
바뀌는 게 본 패턴의 큰 장점.

## 대안 검토

- **JPA 의 second-level cache 로 read 부하 흡수**: cache invalidation 정책이 복잡 (write 후 stale).
  read pattern 이 너무 다양해 cache hit 률이 낮음. cache 와 routing 은 직교 관계 — 같이 가능.
- **Application 레벨 두 EntityManager (write/read)**: JPA 컨텍스트 두 개 관리 부담. 코드에 두
  EM 을 의식적으로 사용해야 — annotation 한 줄로 분리되는 routing 보다 침투적.
- **CQRS 로 read 모델 별도 구축**: 큰 변환. 현재 시스템은 read 모델이 도메인과 거의 동일이라
  과한 변경. 일부 무거운 read 만 별도 read model 로 가는 부분 CQRS 는 ADR-0004 에서 채택
  중이고, 본 ADR 와는 직교.
- **Reader / Writer 패턴 (별도 service)**: 읽기 service 와 쓰기 service 를 코드상 분리 + 다른
  EntityManagerFactory. 표현력은 좋으나 반복 코드 (대부분의 read 는 단순 조회). routing 이
  단순.
- **DB proxy 솔루션 (ProxySQL / pgpool)**: read/write split 을 DB 앞단에서 처리. 인프라 의존성
  추가 + 기능 분기를 application 에서 못 봄 (annotation 으로 의도가 표현 안 됨). 소규모/중규모
  엔 application-level routing 이 단순.

## 결과

- read 부하가 replica 로 분산 — master 의 OLTP 트랜잭션 보호.
- `@Transactional(readOnly=true)` 어노테이션 한 줄로 라우팅 표현 — 코드 침투 최소.
- replica pool 이 별도라 dashboard / SIEM 부하가 결제 connection pool 을 못 잡음.
- prod 만 활성, dev/test 는 단일 DataSource — 인프라 의존성 차등 적용.
- (단점) Replication lag 인지 없이 readOnly 트랜잭션을 만들면 stale data. 코드 리뷰 시 의식
  필요.
- (단점) Master 장애 시 라우팅 자체는 무력 — 다른 layer (DB failover, k8s readiness probe) 가
  필요. routing 은 부하 분산 이지 고가용성 이 아님.
- (단점) Spring 의 `LazyConnectionDataSourceProxy` 트릭이 처음엔 이해하기 어려움 — 주석으로
  의도 강하게 명시.

## 후속 후보

- `@MasterRequired` aspect — 일시적으로 master 강제 (read-after-write hot path).
- 라우팅 metric 노출 — `hikari.connections{pool=master|replica}` + `routing.routed{role=...}`.
- replica lag 모니터링 + lag 가 임계 이상이면 자동으로 master fallback.
- 부분 read 모델 (ADR-0004 의 부분 CQRS) 를 routing 과 결합해 대형 dashboard query 만 별도
  연결로.
- 멀티 replica (Round-Robin / least-loaded) — 현재는 replica 1대 가정.
