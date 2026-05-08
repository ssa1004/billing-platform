# ADR-0032: Hibernate query plan cache + PG prepared statement 튜닝

## 상태
적용

## 배경

JPA / Hibernate 의 *가장 흔한 운영 함정* — 같은 JPQL 한 줄을 매 요청마다 SQL 로 *컴파일* 하는
비용. dev 환경에선 잘 안 보이다가 prod 트래픽이 늘면 CPU 가 *plan compilation 에 발목* 잡혀
응답 latency p99 가 갑자기 튀는 사고로 떠오릅니다.

### 시나리오 — 한 요청이 두 단계로 늦어짐

요청 흐름:

```
[client] → /api/v1/invoices/{id}
            ↓
   InvoiceController.get(id)
            ↓
   invoiceRepository.findById(id)
            ↓
   Spring Data JPA → Hibernate (JPQL "SELECT i FROM InvoiceJpaEntity i WHERE i.id = :id")
            ↓
   ① Hibernate plan compile      ← 한 번만 했으면 좋겠는데 매번 함
            ↓
   ② JDBC PreparedStatement      ← server-side prepared 가 활성화 안 됐으면 PG 도 매번 plan
            ↓
   PG 실행 → 결과 반환
```

두 단계 (①, ②) 모두 캐시가 없으면 *같은 SQL 을 매번 처음 보는 것처럼* 처리. 응답 latency 가
요청량과 비례해 늘어나는 게 아니라 *plan-compilation 시간에 빨려 들어가* 나쁘게 늘어남.

### Hibernate plan cache 의 default 한계

Hibernate 의 default `query.plan_cache_max_size` 는 *2048* 입니다. 큰 도메인 (Invoice / Payment
/ Refund / Wallet / Credit / Webhook / ...) 에서 JPQL / HQL / NativeQuery 를 모두 더하면
2048 을 넘기 쉽고, 한 번 cache full 이 되면 LRU eviction 으로 *hot path 가 plan miss 의 늪에
빠집니다*.

### PG server-side prepared statement 의 흐름

JDBC `PreparedStatement` 는 *client-side* 파라미터 바인딩까지만 — PG server 에서는 매번 fresh
SQL 로 처리할 수도 있고, `prepareThreshold` 회 이후에 *server-side prepared statement* 로
승격할 수도 있습니다. 후자가 되면 PG 도 plan 을 캐시.

```
Client Driver: prepareThreshold=5
  요청 1~4번: 일반 JDBC simple statement (PG 가 매번 plan)
  요청 5번 이후: server-side prepared (PG 가 plan 재사용)
```

`prepareThreshold` 가 기본 5 라 *5번까지는 매번 plan*. high-throughput 환경에서 그 비용은 무시
못함.

### 동적 IN clause 의 함정

```jpql
SELECT i FROM InvoiceJpaEntity i WHERE i.status IN :statuses
```

`statuses` 의 list size 가 매번 다르면 (3개, 7개, 12개...) Hibernate 가 *각 size 마다 다른 SQL*
을 만들어요:

```
WHERE status IN (?, ?, ?)
WHERE status IN (?, ?, ?, ?, ?, ?, ?)
WHERE status IN (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
```

세 개의 *별도 plan* 이 cache 를 차지. plan cache 가 1000개 row 의 list 를 호출당 하나씩 꽂으면
즉시 LRU eviction. 운영자에게는 *원인 모를 응답 지연* 으로 나타남.

## 결정

### Hibernate plan cache 크기 조정

`application.yml`:

```yaml
spring.jpa.properties.hibernate:
  query.plan_cache_max_size: 4096               # default 2048 → 2배
  query.plan_parameter_metadata_max_size: 256
  jdbc.batch_size: 50                           # batch insert/update
  order_inserts: true
  order_updates: true
  query.in_clause_parameter_padding: true       # IN clause padding (아래 참조)
  generate_statistics: true                     # 모니터링 노출
```

- **`plan_cache_max_size: 4096`** — 도메인 query 가 1000개 안팎이라 정확히 그 2배 정도면 *항상
  cache hit*. 메모리는 plan 한 개당 보통 KB 단위, 전체 수십 MB 수준.
- **`generate_statistics: true`** — Hibernate 가 query 실행 횟수 / cache hit / miss 를 micrometer
  / Prometheus 로 노출. cache miss 가 갑자기 튀면 운영 알림.

### PG prepared statement 튜닝

prod profile 의 datasource:

```yaml
hikari:
  data-source-properties:
    prepareThreshold: 5
    tcpKeepAlive: true
    preparedStatementCacheQueries: 512
    preparedStatementCacheSizeMiB: 16
```

- **`prepareThreshold: 5`** — 같은 SQL 이 5번째 호출되면 server-side prepared statement 로 승격.
  default 값이라 사실상 명시 reaffirmation. 0 으로 두면 *항상 server-side*, -1 이면 *항상 simple*.
  high-throughput 도메인에선 0 도 후보지만 PgBouncer transaction-pooling mode 와 충돌할 수 있어
  보수적으로 5 유지.
- **`preparedStatementCacheQueries: 512`** — JDBC level 의 prepared statement 캐시 크기. default
  256 을 넘기면 LRU. 우리 도메인 query 갯수 + buffer.
- **`preparedStatementCacheSizeMiB: 16`** — 캐시 메모리 한도. 전체 메모리 비용 통제.
- **`tcpKeepAlive: true`** — NAT / LB 환경에서 idle TCP connection 이 silent drop 되는 사고
  방지. 짧은 TCP keepalive ping 으로 connection liveness 유지.

### 동적 IN clause padding

Hibernate 의 `query.in_clause_parameter_padding: true` 를 활성화하면 IN clause 의 parameter
size 를 자동으로 (1, 2, 4, 8, 16, 32, ...) 단위로 padding (마지막 값 반복). plan 갯수가 list
size 별로 폭발하지 않고 *log2 단계* 로 압축됩니다.

NativeQuery 는 Hibernate padding 의 영향을 안 받아요. 호출자가 명시 padding 해야 합니다 —
`InClauseSizes.padPow2(list, lastValue)`:

```java
List<UUID> ids = ...;
List<UUID> padded = InClauseSizes.padPow2(ids, ids.get(ids.size() - 1));
em.createNativeQuery("SELECT * FROM payments WHERE id IN (:ids)")
  .setParameter("ids", padded)
  .getResultList();
```

마지막 원소를 반복해 채우는 이유: IN 은 set-membership 이라 duplicate 가 결과에 영향 없음.
*size 만 고정* 시키면 plan 재사용 가능.

### Batch insert 표준 — `jdbc.batch_size: 50`

`saveAll(...)` 같은 흐름에서 50 row 씩 묶어 한 JDBC 호출로 보냄. round-trip 50배 감소. 다만
ID 가 IDENTITY (DB auto-increment) 면 batch 가 동작 안 함 — Hibernate 가 INSERT 한 번마다
generated key 를 받아야 해서. 우리 도메인은 모두 application-side UUID 라 batch 가 잘 동작.

`order_inserts: true` / `order_updates: true` — 같은 entity 의 INSERT / UPDATE 를 묶어 batch
경계 안에 넣음. 한 트랜잭션이 Invoice + InvoiceLine + Payment 를 섞어 만들 때 효과.

## 트레이드오프

### "왜 over-spec 아닌가"

- Plan cache 크기는 *메모리 한도* 안에서 늘리는 거라 비용이 본질적으로 낮습니다. 50MB 정도 더
  쓰는 대신 *tail latency p99 안정화* 를 얻어요.
- `prepareThreshold` / TCP keepalive 는 *PG 와 우리 사이의 통신 정상화* — 안 박아두면 NAT 환경에
서 *원인 모를 connection 끊김* 사고가 운영 두 달 차에 옴.
- IN clause padding 은 *enabled flag 한 줄*. 코드 변경 없음. 위험 0.

### "왜 indexing / query 자체 튜닝부터 안 하는가"

별개의 작업입니다 — query 자체가 느린 건 EXPLAIN ANALYZE 로 잡고, *컴파일 비용* 은 plan cache
로 잡습니다. 이 ADR 은 후자만 다룹니다. 전자는 ADR-0011 (two-layer-cache) / ADR-0017
(multi-tenancy-row-level) 같은 인덱스 설계 ADR 에서.

### Cache full 이후의 LRU 동작

`plan_cache_max_size` 를 넘어가면 Hibernate 는 LRU 로 가장 오래된 plan 을 evict. 그 plan 이
cold path (예: monthly 정산 batch 에서만 쓰는 query) 면 evict 되어도 OK. 매월 한 번 다시
컴파일.

운영 표준은:
1. `generate_statistics` 로 cache miss / hit 메트릭 노출.
2. miss 가 hit 의 1% 미만으로 안정화되면 사이즈 충분.
3. 5% 넘게 튀면 사이즈 부족 → bumping.

### `generate_statistics: true` 의 비용

Hibernate 의 statistics 객체는 atomic counter 들이라 비용이 *나노초 단위*. prod 에서도 켜고
operating. 단점은 application restart 시 통계가 리셋되는 것 — Prometheus 가 그 사이 시점만
누락. 무관한 단점.

### PgBouncer transaction-pooling 과의 충돌

`prepareThreshold > 0` 으로 server-side prepared 가 활성되면, 같은 connection 에 다음 요청이
와야 plan 재사용. PgBouncer 의 transaction-pooling mode (한 transaction 단위로 connection 빌려
줌) 에서는 *다음 transaction 이 다른 connection 으로* 갈 수 있어 prepared statement 가 무용지물.

- 우리 환경: PgBouncer 는 session-pooling mode 또는 미사용 (Hikari 가 application-side pool
  관리). 충돌 없음.
- session-pooling 이면 한 connection 에 같은 client 가 묶여 prepared statement 가 효과.
- transaction-pooling 으로 운영을 옮기게 되면 `prepareThreshold = -1` (server-side disable) 또는
  PgBouncer 의 "max prepared statements" 옵션 (1.21+) 으로 우회.

## 다시 검토할 시점

- Prometheus 의 `hibernate_query_plan_cache_miss_count_total / hibernate_query_plan_cache_hit_count_total`
  비율이 5% 이상으로 안정화되면 `plan_cache_max_size` 를 8192 로 늘림.
- PgBouncer 운영 도입 시 transaction-pooling 호환성 재검토.
- 대규모 분석 query (수만 row 의 IN clause) 가 도메인에 들어오면 IN clause 자체를
  CTE / temp table 로 변환하는 별도 패턴 검토.
- Spring Boot / Hibernate major upgrade 시 default 값 (plan_cache_max_size 등) 변경 확인.
