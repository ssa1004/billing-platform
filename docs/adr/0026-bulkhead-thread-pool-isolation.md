# ADR-0026: 외부 호출 ThreadPool Bulkhead 격리

## 상태
적용

## 배경

ADR-0008 의 Resilience4j Circuit Breaker (서킷브레이커) 는 *실패 누적 시 호출 차단* 으로 자원
보호. 그런데 **CB OPEN 직전 — 즉 호출이 *느려지는* 단계** 가 위험합니다.

### 실제 시나리오 (Hystrix 가 풀던 cascade failure)

1. PG 호출이 평소 200ms → 갑자기 4초로 슬로우다운 (PG 측 일시 장애 / 네트워크 지연).
2. 결제 endpoint 가 servlet thread 위에서 그대로 블로킹 → 200 호출 동시 처리 시 200 thread
   가 전부 PG 응답 대기.
3. 그 사이 들어오는 *다른 endpoint* (wallet 조회, invoice 발급, audit timeline) 도 같은
   servlet thread pool 을 사용 → thread 가 안 남아 *대기 큐 쌓임*. 결국 timeout 으로 떨어짐.
4. CB 가 50% failure threshold 넘기 전엔 (호출은 *느려도* 일단 완료) OPEN 으로 안 가서 자원
   보호가 안 됨. CB OPEN 되어도 이미 thread pool 은 고갈된 상태.

이게 Netflix Hystrix 가 풀던 cascade failure (한 종속이 느려져 caller 까지 무너지는 현상).
카카오 / Line / 토스 페이먼츠 모두 *도메인별 ThreadPool 격리* 로 같은 문제 풀고 있음.

## 결정

**Resilience4j ThreadPoolBulkhead** 로 외부 호출을 *별도 worker pool* 위에서 실행. 도메인별
세 격리:

| 격리 인스턴스 | 용도 | core / max / queue |
|---|---|---|
| `pg` | PG (외부 결제 게이트웨이) authorize / refund / lookup | 10 / 20 / 50 |
| `webhook` | customer webhook fan-out 발송 | 15 / 30 / 100 |
| `audit-export` | SIEM / 회계 audit batch export (무거운 IO) | 2 / 5 / 20 |

### 흐름

```
ApplicationService (servlet thread, 가상 스레드)
   │   submit
   ▼
ThreadPoolBulkhead "pg"
   │   ├─ 가득 차면 → BulkheadFullException → 503 + Retry-After
   │   └─ 여유 있으면 worker 가 호출 실행
   ▼
RestClientPgClient (ADR-0008 의 CB + Retry)
   │
   ▼
외부 PG HTTP
```

### Pool 산정 (Little's law)

`동시 호출 수 = 처리량 × 평균 latency`

PG 의 경우:
- 처리량 (TPS) ≈ 10 req/s (정상시)
- 평균 latency ≈ 200ms (P50), 1s (P99)
- 정상 동시 호출 ≈ 2~10
- 슬로우다운 시 latency 4s → TPS 그대로면 동시 ≈ 40

→ maxThreadPoolSize=20, queueCapacity=50 (총 70) 면 슬로우다운 시점에 *남는 호출은 fast-fail*
하면서 자원은 보호. coreThreadPoolSize=10 으로 평상시 과한 thread 생성 막음.

webhook 은 fan-out 이라 동시 호출이 더 많을 수 있음 (이벤트 1건당 endpoint 여러 곳) → 두 배.
audit-export 는 무거운 IO 라 *적은 worker* 로 격리 (한 번에 많이 돌면 다른 격리 풀에도 압력).

### 왜 ThreadPool 이 Semaphore 보다 좋은가

Resilience4j 는 두 종류 Bulkhead 지원:

- **Semaphore**: 호출 스레드 *자체* 의 동시성만 제한. 호출은 caller thread 에서 그대로 실행.
- **ThreadPool**: 별도 worker thread 가 호출 실행. caller 는 future 만 받음.

Semaphore 의 한계: PG 가 슬로우다운되면 *caller thread 자체가 PG 응답 대기로 묶임*. 동시성
제한은 해도 *이미 잡힌 caller thread* 는 풀리지 않아 cascade 차단 못함. ThreadPool 은 caller
가 future timeout 으로 짧게 wait → worker 만 막혀도 caller 는 풀림.

따라서 *cascade 차단의 본질적 효과* 는 ThreadPool 만 가능. 본 ADR 의 핵심 결정.

### 왜 동기 인터페이스 유지인가

`@Bulkhead(type=THREADPOOL)` annotation 은 메서드가 `CompletableFuture` 반환을 요구. 그러나
ApplicationService 까지 비동기로 바꾸려면 침투가 큼 — 결제 / 환불 흐름 전체가 비동기로 변환
되어야 함.

본 구현은 절충안:
- `BulkheadedPgClient` 데코레이터가 PgClient 인터페이스 (동기) 를 그대로 implement.
- 내부에서 `bulkhead.executeSupplier(...)` 로 별도 worker 에서 실행 + caller 는 짧은 timeout
  으로 future.get() 대기.
- caller 의 *동기 시그니처* 는 유지하면서, *worker 는 격리 풀* 에 있어 cascade 차단 효과는 그대로.

핵심 trade-off: caller thread 도 wait 하긴 함 (가상 스레드라 OS thread 는 안 잡힘). 그래도
*bulkhead 가득 차면 즉시 fast-fail* + *worker 풀이 별도라 다른 도메인 endpoint 영향 없음* —
두 핵심 효과는 살아있음.

가상 스레드 (Java 21) 와 결합되니 caller wait 의 비용이 *플랫폼 thread 의 그것과 자릿수 다름*.
이 조합이 본 결정을 단순화시킨 핵심 인프라.

### Bulkhead 가득 → 503 + Retry-After

`BulkheadFullException` 을 GlobalExceptionHandler 에서 잡아 `503 SERVICE_UNAVAILABLE` +
`Retry-After: 1` 헤더로 응답. 클라이언트가 짧은 backoff 후 재시도하면 풀에 여유 생겨 정상
처리.

PG 호출의 fallback 은 더 명확:
- `authorize` → `BULKHEAD_FULL` errorCode 의 reject 응답. 클라이언트 retry.
- `refund` → 같은 패턴.
- `lookup` (reconciler 가 부르는 idempotent 조회) → `IN_PROGRESS` 로 fallback. 다음 batch
  cycle 에 다시 lookup.

### CB / Retry / Bulkhead 의 중첩 순서

표준 권장: `Bulkhead → CircuitBreaker → Retry → 실제 호출`. 본 구현은:

```
ApplicationService
   ↓
BulkheadedPgClient   (Bulkhead — 외부)
   ↓
RestClientPgClient   (CircuitBreaker + Retry — 내부, ADR-0008)
   ↓
HTTP
```

Bulkhead 가 가장 바깥 → worker 풀이 가득 차면 CB / Retry 까지 가지 않고 즉시 fast-fail.
worker 안에 들어가면 CB 가 OPEN 인지 확인, OPEN 이면 fallback. CLOSED 면 Retry annotation
이 일시 장애 흡수 후 실제 호출. 모든 layer 의 의도 명확.

### Dev / Test 비활성

`billing.pg.enabled=false` 인 환경 (로컬 dev / test) 에서는 `MockPgClient` 가 활성. Bulkhead
는 *외부 호출의 자원 보호* 용이라 Mock 호출에 의미 없음. `@ConditionalOnProperty` 로 prod
에서만 활성.

## 대안 검토

- **Semaphore Bulkhead**: 위에서 설명. cascade 차단 효과 부족.
- **호출 자체를 비동기 (CompletableFuture) 로 변환**: 도메인 service 까지 침투. 큰 변경 + 도메인
  로직이 비동기 콜백 chain 으로 분산됨. 가상 스레드와 결합된 동기 model 이 이해 단순.
- **별도 microservice 로 PG 호출만 분리**: cascade 는 막을 수 있으나 분산 시스템 운영 복잡도 +
  네트워크 hop 추가 + 인프라 변경 큼. 응답 속도 저하 가능. ADR-0001 의 *모듈러 모놀리스* 방향
  과 충돌.
- **Servlet thread pool 자체를 도메인별로 분리** (Tomcat custom executor): 가능. Spring MVC 가
  `AsyncTaskExecutor` 로 도메인별 분기를 잘 지원하지만 *외부 호출과 servlet 처리* 가 같은
  thread 에서 이뤄지는 한 격리 효과 부족.
- **Queue (Kafka) 로 PG 호출 비동기화**: 결제 흐름이 *동기 응답이 자연스러운* 도메인이라
  부적합. 응답 시점에 PG 결과를 client 에 줘야 하는 사용자 경험. webhook / audit-export 는
  자연스러움.

## 결과

- PG / webhook / audit-export 가 *도메인별 격리 풀* 로 분리 — 한쪽 슬로우다운이 다른 쪽으로
  cascade 안 됨.
- Bulkhead full 시 fast-fail 로 자원 보호 + 503 + Retry-After 헤더로 client 가 안전하게 재시도.
- CB + Retry + Bulkhead 가 명확한 순서로 중첩 — 각 layer 의 책임 분명.
- 가상 스레드 환경에서 동기 인터페이스 유지 — application service 침투 없음.
- (단점) Worker 가 별도라 thread context (MDC, tracing) 가 자동 propagation 되지 않음 — Resilience4j
  는 contextPropagator 옵션 제공. **ADR-0027 에서 적용** (MdcContextPropagator).
- (단점) Pool 산정이 운영 metric 없이 *추정* — 6개월 후 실측 latency / TPS 기반 재조정.
- (단점) `BulkheadedPgClient` 의 caller-side timeout (6s) 과 RestClient read timeout (5s) +
  Retry 합산 (200+400+800ms) 이 비슷한 수준 — 두 timeout 이 거의 동시 발화. 운영 metric 보고
  조정.

## 후속 후보

- ~~`ContextPropagator` 로 MDC (traceId, requestId) 를 worker 까지 전파.~~ → ADR-0027 에서 적용.
- Bulkhead metric 노출 — `resilience4j_bulkhead_available_threads{name=pg}`,
  `resilience4j_bulkhead_queued_calls{name=pg}` → Prometheus + Grafana 대시보드.
- `webhook` / `audit-export` 격리 풀의 실제 사용처 wiring (현재는 `pg` 만 wire). webhook
  delivery 와 audit export batch 도 각자 풀에서 실행되도록.
- TimeLimiter 추가 — 호출 자체에 timeout 명시 (현재는 RestClient read timeout 만).
- Adaptive bulkhead — 운영 P95 latency 변동에 맞춰 pool size 자동 조정.
