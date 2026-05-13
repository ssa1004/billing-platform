# Load test (k6)

billing-platform 의 결제 / 청구 / 정산 endpoint 5종 부하 시나리오. 단순 RPS / latency
측정에 더해 본 플랫폼 특유의 invariant — `Idempotency-Key` 24h 응답 캐시 hit, advisory
lock 직렬화, multi-currency 분리 집계 — 가 부하 상황에서도 동작하는지를 회귀 가드한다.

## 디렉토리

```
load/
├── README.md
└── k6/
    ├── lib/
    │   ├── auth.js          # K6_TOKEN env helper + Idempotency-Key 생성
    │   └── config.js        # BASE URL + customer / ResourceType / period / currency pool
    └── scenarios/
        ├── usage-event-ingest.js   # POST /api/v1/usage — metering throughput (500 req/s)
        ├── invoice-issue.js        # POST /api/v1/settlement/run — 정산 → invoice 발행 (ramping 0→100 VU)
        ├── invoice-query.js        # GET  /api/v1/invoices — v1 + v2 첫 페이지 (300 req/s)
        ├── payment-charge.js       # POST /api/v1/payments — Idempotency-Key 24h 응답 캐시 (100 req/s)
        └── aged-receivables.js    # GET  /api/v1/aged-receivables — 집계 쿼리 (50 req/s)
```

## 사전 준비

세 가지 방법 중 하나:

### A. brew 로 로컬 설치

```bash
brew install k6
k6 version
```

### B. docker 직접 실행

```bash
docker run --rm -i grafana/k6 run - < load/k6/scenarios/usage-event-ingest.js
```

### C. docker-compose profile

`infrastructure/docker-compose.yml` 에 `profile: load` 로 k6 서비스가 추가되어 있다:

```bash
docker compose -f infrastructure/docker-compose.yml --profile load run --rm k6 \
  run /scripts/scenarios/usage-event-ingest.js
```

## 통합 환경 기동

본 부하 시나리오는 본 앱이 이미 떠 있는 상태에서 endpoint 를 친다.

### dev 단독 (H2 + Mock PG)

```bash
./gradlew :billing-bootstrap:bootRun
# → http://localhost:8080
```

### prod 프로필 (Postgres + Redis + Kafka + Wiremock)

```bash
docker compose -f infrastructure/docker-compose.yml up -d postgres redis kafka wiremock
SPRING_PROFILES_ACTIVE=prod ./gradlew :billing-bootstrap:bootRun
```

### 통합 compose (cross-repo demo — auth-stub / notification-stub 포함)

```bash
docker compose -f infrastructure/docker-compose.integration.yml up -d --wait
BILLING_OUTBOX_RELAY_ENABLED=true \
  SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
  ./gradlew :billing-bootstrap:bootRun
./scripts/integration-demo.sh   # customer / order seed
```

### 시나리오 seed (부하용)

본 플랫폼의 시나리오는 미리 seed 된 데이터가 있어야 부하 모델이 의미를 갖는다.

| 시나리오 | 필요한 seed | 없으면 |
|---|---|---|
| usage-event-ingest | 없음 — eventId 가 매번 새 UUID | 정상 동작 |
| invoice-issue | `K6_SETTLEMENT_CUSTOMERS` 의 customer + PricingPlan + 해당 period 의 usage | 4xx (SETTLEMENT_NO_USAGE) 로 떨어져 invoice 발행 metric 이 0 |
| invoice-query | `K6_CUSTOMERS` 의 customer + 발행된 invoice | 200 + `[]` (빈 배열) — latency 만 측정 가능 |
| payment-charge | `K6_ORDER_IDS` 의 미리 발행된 주문 ID | 404 ORDER_NOT_FOUND — Idempotent-Replayed 헤더가 안 붙어 cache-hit-ratio threshold 가 의미 없어짐 |
| aged-receivables | `K6_CUSTOMERS` × KRW/USD/JPY 통화 분리 invoice | 200 + 빈 rows — multi-currency 분리 집계 신호가 안 보임 |

부하 측정 의도가 *모든 시나리오를 한 번에 의미 있게 돌리는 것* 이라면 다음 순서로
seed 를 미리 만들고 시작한다:

1. `acme-corp-1..8` customer + PricingPlan (KRW / USD / JPY 모두)
2. `K6_PERIOD` (기본 현재 월) 의 usage event 다량
3. 정산 한 번 트리거 → invoice 생성
4. `acme-corp-1..N` 명의 order 한 명당 5건씩 미리 발행 → 그 ID 들을 `K6_ORDER_IDS` 로 주입

## 시나리오별 실행

### 1) usage-event-ingest — 사용량 이벤트 수신 (metering throughput)

다른 레포 (bid-ask-marketplace / gpu-job-orchestrator) 가 발사한 usage event 가 모두 이
endpoint 로 모인다. `eventId` 가 PK 겸 UNIQUE 제약이라 INSERT + (있다면) UNIQUE check
만의 가벼운 write path. 가장 hot 한 write 의 정상 path throughput.

```bash
k6 run load/k6/scenarios/usage-event-ingest.js
```

| metric | 기준 |
|---|---|
| `http_req_duration` p95 / p99 | < 50ms / < 150ms |
| `http_req_failed` | < 1% |
| `usage_ingest_accepted` | > 99% (`accepted=true` 비율 — 새 eventId 라 거의 100%) |

### 2) invoice-issue — 월 정산 → 청구서 발행 (ramping VU)

운영자 수동 정산 트리거 endpoint. 평소는 Spring Batch MonthlySettlementJob 이 친다.
같은 (customer, period) 의 동시 호출은 `pg_advisory_xact_lock(settlement:cust:202605)`
으로 직렬화 — ramping VU 0 → 100 으로 lock 대기 분포를 본다.

```bash
k6 run load/k6/scenarios/invoice-issue.js
```

| metric | 기준 |
|---|---|
| `http_req_duration` p95 / p99 | < 500ms / < 1500ms |
| `http_req_failed` | < 2% (4xx ALREADY_FINALIZED 는 의도된 응답) |
| `invoice_advisory_lock_timeout` | < 10 (5xx 로 떨어진 lock 대기 timeout — 0 에 가까워야) |

custom counter:
- `invoice_issued_count` — 첫 정산 성공 횟수
- `invoice_already_finalized_count` — 같은 (cust, period) 의 idempotent 응답

### 3) invoice-query — 청구서 목록 조회 (read-heavy)

운영자 대시보드 / 고객 self-service 화면이 가장 자주 치는 read endpoint. `(customer_id,
created_at DESC)` 인덱스를 hit 하는 단순 path. v1 + v2 두 endpoint 를 매 iteration 모두
호출해 두 응답 schema 분기의 latency 를 함께 측정 (v2 는 currency 필터링 추가).

```bash
k6 run load/k6/scenarios/invoice-query.js
```

| metric | 기준 |
|---|---|
| `http_req_duration` p95 / p99 | < 100ms / < 300ms |
| `http_req_failed` | < 1% |
| `invoice_query_v1_ok` | > 99% (v1 첫 페이지 200 비율) |
| `invoice_query_v2_ok` | > 99% (v2 + currency 필터링 200 비율) |

> task 명세의 cursor pagination 은 본 시점 endpoint 가 노출하지 않아 (`limit` 만 받음)
> 첫 페이지 latency 측정으로 대체. 후속 개선 항목으로 `starting_after` 토큰 추가가
> 있으면 본 시나리오에서 그대로 cursor 분기 가드 추가 가능.

### 4) payment-charge — 결제 처리 + Idempotency-Key 24h 응답 캐시

Stripe API 표준 처방 (같은 Idempotency-Key 재호출 시 첫 응답을 24h 동안 그대로 반환).
`IdempotencyResponseCacheFilter` 가 `Idempotent-Replayed: true` 헤더를 붙여 client 가
replay 임을 알 수 있게 한다 (ADR-0028). 80% 새 키 (cache miss) + 20% 같은 키 재호출
(cache hit) 으로 섞어 cache hit 비율을 직접 측정.

```bash
# orderId seed 가 있어야 cache hit ratio threshold 가 의미 있다
K6_ORDER_IDS="<uuid1>,<uuid2>,..." \
  k6 run load/k6/scenarios/payment-charge.js
```

| metric | 기준 |
|---|---|
| `http_req_duration` p95 / p99 | < 200ms / < 600ms |
| `http_req_failed` | < 5% (seed 없을 때 404 가 일부 섞이는 걸 허용) |
| `idempotency_cache_hit_ratio` | > 80% (재호출 path 에 한정한 비율) |

custom counter:
- `payment_cache_hit_count` — `Idempotent-Replayed: true` 헤더로 떨어진 호출
- `payment_cache_miss_count` — 같은 키 재호출 path 인데 헤더가 안 붙은 호출
- `payment_order_not_found` — orderId seed 누락 신호

### 5) aged-receivables — 미수금 집계 조회 (read, multi-currency 분리)

(customer × currency) 별 aging bucket (current / 31-60 / 61-90 / 90+ 일) 집계. 같은
customer 가 KRW / USD / JPY invoice 를 갖고 있으면 응답에 3 row. constant 50 req/s 로
운영자 대시보드 polling 모사. read-replica 라우팅 ([ADR-0025]) 이 정상이면 invoice-issue
/ payment-charge 의 write 부하와 자원 경합이 없어야 한다 (둘을 동시에 돌렸을 때 본
시나리오의 p95 가 흔들리지 않으면 라우팅 정상 신호).

```bash
k6 run load/k6/scenarios/aged-receivables.js
```

| metric | 기준 |
|---|---|
| `http_req_duration` p95 / p99 | < 300ms / < 800ms |
| `http_req_failed` | < 1% |
| `aged_currency_diversity` | > 0 (응답에 currency 2종 이상이 분리된 호출 비율) |
| `aged_response_rows` | seed 에 따라 다름 — sanity check 용 Trend |

> task 명세의 `?asOf=...` query param 은 본 시점 endpoint 가 받지 않는다 (asOf 는
> server 의 `Instant.now()`). 후속 개선 항목으로 asOf 시점 전달이 들어가면 본 시나리오의
> `K6_AGED_AS_OF` 환경변수가 그대로 쓰인다.

## 한 번에 실행

```bash
./scripts/run-load.sh
```

usage-event-ingest → invoice-issue → invoice-query → payment-charge → aged-receivables
순으로 단계 실행. 결과는 `build/k6-reports/{scenario}.json` 에 떨군다.

write 시나리오 (ingest / invoice-issue) 가 먼저 데이터를 적재한 뒤 read 시나리오
(query / aged-receivables) 가 그 위에서 응답 latency 를 측정하는 순서 — 빈 응답이
threshold 를 왜곡하지 않도록 설계.

## 환경변수

| key | 기본 | 설명 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | HTTP base. 단독 bootRun = `:8080` |
| `K6_TOKEN` | (빈 값) | 운영 환경에서 Authorization Bearer 토큰. dev 는 비워둠 |
| `K6_CUSTOMERS` | `acme-corp-1..8` | 시나리오 round-robin customer pool (CSV) |
| `K6_SETTLEMENT_CUSTOMERS` | `acme-corp-1..4` | invoice-issue 가 advisory lock 경합을 만들 좁힌 풀 |
| `K6_PERIOD` | 현재 월 (`YYYY-MM`) | invoice-issue 의 정산 period |
| `K6_PERIODS` | (= `K6_PERIOD`) | multi-period 분기용 (CSV) |
| `K6_CURRENCIES` | `KRW,USD,JPY` | invoice-query / aged 의 currency 풀 |
| `K6_ORDER_IDS` | (빈 값) | payment-charge 의 orderId pool (CSV) — seed 가 없으면 404 |
| `K6_AGED_AS_OF` | (빈 값) | aged-receivables 의 asOf 시점 (현재 endpoint 는 server 시점 사용) |

## billing 특유 측정 항목

본 플랫폼이 단순 REST 백엔드와 다른 측정 항목:

| metric | 의미 |
|---|---|
| **`metering_lag`** | usage event 가 INSERT 된 시각과 AggregateUsageJob 이 집계에 반영한 시각의 차이. 본 시나리오는 client 단에서 직접 측정 불가 — `actuator/metrics` 의 `billing.metering.lag.seconds` (Micrometer) 와 같은 시간축에 plot 해 ingest 부하 중에 lag 가 늘지 않는지 본다. |
| **`idempotency_cache_hit_ratio`** | 같은 Idempotency-Key 재호출 시 `Idempotent-Replayed: true` 헤더가 붙은 비율. payment-charge 시나리오에서 직접 측정. 24h 응답 캐시 (ADR-0028) 가 정상 동작하는 신호. |
| **`saga_compensation_count`** | 결제 실패 → ledger 보상 트랜잭션 → outbox `payment.compensated` 발행의 카운트. client 단 직접 측정은 불가 — server 측 `billing.saga.compensation.count` counter 와 동시 plot. payment-charge 시나리오의 `payment_order_not_found` 와 합쳐 보상 경로의 throughput 가드. |
| **`advisory_lock_wait_ms`** | invoice-issue 시나리오의 ramping VU 가 같은 (cust, period) 에 부딪칠 때 `pg_advisory_xact_lock` 대기 시간. client 단에선 `http_req_duration` 의 tail 로만 보이고 정확한 lock 대기 분포는 server 측 `billing.settlement.advisory_lock.wait_ms` Timer 를 같은 시간축에 plot 해야 한다. |
| `usage_ingest_accepted` | usage event 의 `accepted=true` 비율 (eventId 중복 검출). |
| `invoice_issued_count` / `invoice_already_finalized_count` | invoice 발행 성공 / idempotent 응답 카운트. |
| `invoice_advisory_lock_timeout` | 5xx 로 떨어진 lock 대기 timeout 카운트 — 0 에 가까워야 한다. |
| `invoice_query_v1_ok` / `invoice_query_v2_ok` | v1 / v2 첫 페이지 200 비율 — 회귀 가드. |
| `payment_cache_hit_count` / `payment_cache_miss_count` | Idempotent-Replayed 헤더 동작 raw count. |
| `payment_order_not_found` | 404 카운트 — seed 부족 신호. |
| `aged_currency_diversity` | 응답에 currency 2종 이상이 분리된 호출 비율. multi-currency 부하의 sanity check. |
| `aged_response_rows` | 응답 row 분포 — seed 와 데이터 적재 정도의 sanity. |

> **client 단에서 직접 측정 불가능한 metric (metering_lag / saga_compensation_count /
> advisory_lock_wait_ms) 은** server 측 Micrometer counter 와 k6 결과를 같은 시간축에
> 올려야 의미를 본다. 본 시나리오의 부하 시점에 actuator/metrics scrape 를 함께 떠서
> Grafana 에 plot 하는 게 표준.

## k6 metric 해석 (참고)

| metric | 의미 |
|---|---|
| `vus` / `vus_max` | 현재 / 최대 VU |
| `iter_duration` / `iteration_duration` | 한 default 함수 실행 시간 |
| `http_req_duration` | HTTP 응답 소요 — connect / TLS / waiting 합 |
| `http_req_waiting` | TTFB — server-side latency 의 근사 |
| `http_req_failed` | non-2xx 비율 |
| `data_received` / `data_sent` | byte 카운터 |

### p95 / p99 보는 법

- **p95** 는 일상 SLO 의 변동성 신호 (95 백분위).
- **p99** 는 꼬리 신호 — GC, advisory lock 경합, PG (외부 결제 게이트웨이) 호출 지연,
  HikariCP 풀 고갈 같은 드문 이벤트.
- p95 → p99 격차가 크면 운영 환경의 reliability tail 이 두꺼운 것 — invoice-issue
  의 경우 advisory lock 대기 분포가 long-tail 일 가능성이 가장 크다 (`pg_locks` view +
  `pg_stat_activity` 의 wait_event=`Lock` 비율을 함께 본다).

### 시나리오별 부하 모델

| 시나리오 | executor | rate / VU |
|---|---|---|
| usage-event-ingest | constant-arrival-rate | 500 req/s, 60s, preAllocated 100 |
| invoice-issue | ramping-vus | 0 → 25 → 50 → 100 VU, 80s |
| invoice-query | constant-arrival-rate | 300 req/s × 2 GET, 60s, preAllocated 60 |
| payment-charge | constant-arrival-rate | 100 req/s, 60s, preAllocated 40 |
| aged-receivables | constant-arrival-rate | 50 req/s, 60s, preAllocated 15 |

`constant-arrival-rate` 는 throughput / latency 측정 (write-light 또는 read-heavy 시나리오).
`ramping-vus` 는 concurrency 기준 — invoice-issue 의 advisory lock 경합 측정처럼 동시
호출 수가 임계 지점을 만드는 시나리오에 적합하다.

## 결과 예시 (참고 — 환경마다 다름)

m1 max + docker-compose 통합 (Postgres + Redis + Kafka + Wiremock, 1 instance, 4 cpu,
2G heap) 기준 대략적인 예시:

```
usage-event-ingest
  http_req_duration........... avg=15ms     p(95)=38ms   p(99)=110ms
  http_req_failed............. 0.02%
  usage_ingest_accepted....... 99.9%

invoice-issue (ramping 0→100 VU)
  http_req_duration........... avg=180ms    p(95)=420ms  p(99)=1200ms
  http_req_failed............. 0.5%
  invoice_issued_count........ 80
  invoice_already_finalized_count 1820
  invoice_advisory_lock_timeout 0

invoice-query (v1 + v2)
  http_req_duration........... avg=25ms     p(95)=72ms   p(99)=180ms
  http_req_failed............. 0.0%
  invoice_query_v1_ok......... 100%
  invoice_query_v2_ok......... 100%

payment-charge (orderId seed 있음)
  http_req_duration........... avg=68ms     p(95)=170ms  p(99)=440ms
  http_req_failed............. 0.8%
  idempotency_cache_hit_ratio. 92%
  payment_cache_hit_count..... 280
  payment_cache_miss_count.... 25

aged-receivables (multi-currency seed 있음)
  http_req_duration........... avg=85ms     p(95)=220ms  p(99)=580ms
  http_req_failed............. 0.0%
  aged_currency_diversity..... 85%
  aged_response_rows.......... avg=12
```

## 더 나아가려면

- 5 시나리오의 결과를 `build/k6-reports/*.json` 으로 떨궈서 dashboard 에 plot.
- `--out experimental-prometheus-rw=http://prom:9090/api/v1/write` 로 Prometheus
  remote-write — k6 metric 과 본 플랫폼의 actuator/prometheus metric
  (`billing.metering.lag.seconds`, `billing.settlement.advisory_lock.wait_ms`,
  `billing.saga.compensation.count`) 을 같은 시간축에 올린다.
- order / customer / PricingPlan / period 별 usage 의 seed 전용 스크립트 분리 —
  현재 통합 시연용 (`scripts/integration-demo.sh`) 은 1 customer 만 만든다. 부하 측정
  전용 seed 스크립트 (`scripts/seed-load-data.sh`) 가 있으면 더 깔끔.
- 더 큰 부하는 k6 cloud / k6 distributed mode 필요 — 본 시나리오는 single-node
  VU 100 ~ 400 선 운용.
