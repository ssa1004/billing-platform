# ADR-0027: Bulkhead worker thread 로의 MDC ContextPropagator

## 상태
적용

## 배경

ADR-0026 의 ThreadPoolBulkhead 격리는 PG / webhook / audit-export 호출을 별도 worker pool
에서 실행. 장애 전파 차단 효과는 분명한데 관측 가능성 한 가지가 누락됐습니다.

### 문제 — worker thread 에 MDC 가 없음

SLF4J 의 MDC (Mapped Diagnostic Context) 는 `ThreadLocal` 기반. 우리 logging pattern 은
이렇게 설정되어 있어 매 로그 라인에 `traceId / spanId / requestId` 가 박힙니다 (`application.yml`):

```
%5p [billing-platform,%X{traceId:-},%X{spanId:-},%X{requestId:-}]
```

ThreadPoolBulkhead 가 caller (servlet / 가상 스레드) 의 작업을 별도 worker 에 submit 하면
ThreadLocal 은 자동으로 따라가지 않습니다. 결과:

1. 결제 endpoint → `RestClientPgClient.authorize` 가 worker thread 에서 실행.
2. RestClient 가 찍는 `Sending POST /authorize ...` 로그에 traceId 가 비어있음.
3. Tempo / Loki / Datadog 에서 한 결제 trace 를 따라가다가 PG 호출 구간에서 join 이 끊김.
4. PG 가 5xx 를 던졌을 때 그 에러가 어느 customer / 어느 결제와 묶이는지 grep 으로 추적 불가.

같은 문제로 Spring SecurityContext, Sleuth/Brave Span, 우리 자체의 customer-id MDC 도 worker
에서 비어있음.

### 운영에서 본 같은 패턴

- Resilience4j 자체가 `ContextPropagator` 인터페이스를 표준 명세로 제공.
- Spring Cloud Sleuth (legacy) / Micrometer Tracing → `TraceableExecutorService` 로 wrap.
- 분산 추적이 도입된 webhook fan-out 시스템들은 어디서나 같은 propagator 패턴이 필요해집니다.

## 결정

**Resilience4j 의 `ContextPropagator<T>` 인터페이스 구현체 `MdcContextPropagator` 를 모든
ThreadPoolBulkhead 인스턴스 (pg / webhook / audit-export) 에 등록.**

### 인터페이스 명세 — 세 단계

```java
public interface ContextPropagator<T> {
    Supplier<Optional<T>>  retrieve();   // caller thread 에서 호출 — snapshot 추출
    Consumer<Optional<T>>  copy();        // worker thread 진입 시 호출 — ThreadLocal 셋업
    Consumer<Optional<T>>  clear();       // worker 작업 끝 — ThreadLocal 정리
}
```

세 단계가 분리된 이유: ThreadPool 의 worker reuse 에서 이전 작업의 ThreadLocal 이 남아 있는
사고를 막기 위함. clear 단계가 없으면 작업 1 의 traceId 가 작업 2 의 로그에 그대로 따라가
관측 가능성을 오히려 악화시킴.

### 우리 구현 — 통째 전파

```java
public final class MdcContextPropagator implements ContextPropagator<Map<String,String>> {
    public Supplier<Optional<Map<String,String>>> retrieve() {
        return () -> Optional.ofNullable(MDC.getCopyOfContextMap())
                              .filter(m -> !m.isEmpty())
                              .map(Map::copyOf);
    }
    public Consumer<Optional<Map<String,String>>> copy() {
        return ctx -> ctx.ifPresent(MDC::setContextMap);
    }
    public Consumer<Optional<Map<String,String>>> clear() {
        return ctx -> MDC.clear();
    }
}
```

### 왜 통째 전파인가 — whitelist 식 거부

대안으로 특정 키만 (`traceId`, `requestId`, `customerId`) 골라 전파하는 방식이 있지만:

- 새 MDC 키 추가 시마다 propagator 코드 수정 — 운영 중 누락 위험.
- customer-id, tenant-id, saga-id, idempotency-key 등 도메인 운영의 MDC 가 늘어날수록 부담.
- snapshot 비용 (`Map.copyOf`) 이 보통 < 10us — 무시 가능.

→ 통째 전파 + Map 불변 복사로 caller mutation 격리.

### 왜 빈 MDC 는 `Optional.empty()` 인가

`MDC.getCopyOfContextMap()` 은 비어있을 때 null 을 반환 (logback 구현 기준). 빈 Map 을 그대로
넘겨 worker 에서 `MDC.setContextMap(emptyMap)` 하면 이미 비어있던 worker MDC 를 빈 값으로
명시 덮어쓰기 — 의미적으로 같지만 디버깅 시 propagator 가 작동했는지 안 했는지 헷갈림.
`Optional.empty()` 면 `copy().accept()` 가 no-op 라 의도가 분명.

### 등록 — `ThreadPoolBulkheadConfigCustomizer` per instance

yaml 의 `resilience4j.thread-pool-bulkhead.instances.{pg,webhook,audit-export}` 설정은 그대로 두고
(pool size / queue capacity 는 운영 변수), propagator 만 코드로 합쳐 넣기:

```java
@Bean
public ThreadPoolBulkheadConfigCustomizer pgBulkheadMdcCustomizer(MdcContextPropagator p) {
    return ThreadPoolBulkheadConfigCustomizer.of("pg",
            builder -> builder.contextPropagator(p));
}
// webhook, audit-export 도 동일
```

`ThreadPoolBulkheadConfigCustomizer.name()` 이 정확히 한 인스턴스만 매칭. 인스턴스가 늘어나면
빈 한 개씩 추가.

### `RequestAttributes` (Spring SecurityContext / RequestContextHolder) 는 추후

Spring Security 의 `SecurityContextHolder`, MVC 의 `RequestContextHolder` 도 같은 ThreadLocal
패턴. 본 ADR 범위는 MDC 만 — `SecurityContextPropagator` 는 후속에서.

이유:
- 현재 PG / webhook 호출이 SecurityContext 에 의존하지 않음 (외부 호출은 인증 키로만 — endpoint
  의 secret).
- audit-export 는 시스템 batch 라 user context 자체 부재.
- SecurityContext 까지 넣으면 권한 누수 위험 (잘못된 customer context 가 worker 에 따라가는
  실수). 분리해서 설계 검토 후 별도 ADR.

## 대안 검토

- **MDC 를 호출자가 명시 인자로 넘김** — 모든 외부 호출 시그니처 (`PgClient#authorize`,
  `WebhookHttpClient#send`) 에 traceId 등을 인자 추가. 침투적 + adapter 가 운영 관심사 (관측
  가능성) 를 도메인 시그니처로 끌어올림. 거부.
- **`TraceableExecutorService` (Micrometer Tracing) 으로 worker 풀 자체를 wrapping** — Resilience4j
  의 Bulkhead 가 자체 ExecutorService 를 캡슐화하고 있어 그 안의 executor 만 wrapping 어려움.
  외부 wrapping 으로 가능하지만 우리 yaml 설정과 안 맞음.
- **InheritableThreadLocal 로 logback 설정** — child thread 에 자동 상속. 그러나 thread reuse
  를 안 함 (새 thread 만들 때 한 번 복사). ThreadPool 이 thread reuse 라 첫 작업의 MDC 가 그 후
  계속 박혀 있게 됨. 정확히 우리가 막고 싶은 사고. 거부.
- **CompletableFuture 패턴으로 async 인 채로 caller 가 직접 MDC 관리** — application service 까지
  비동기 침투 (ADR-0026 의 거부 이유와 동일).

## 결과

- PG / webhook / audit-export 의 모든 worker 로그에 traceId / spanId / requestId / customerId 가
  찍힘 → 분산 추적 join 이 끊기지 않음.
- 새 MDC 키가 추가되어도 propagator 수정 없이 자동 전파.
- Worker thread 의 MDC 가 매 작업 후 clear 되어 thread reuse 시 noise leakage 차단.
- (단점) 매 worker submit 마다 `Map.copyOf` 비용 — < 10us 수준이라 무시 가능. 운영 metric 추가
  검토.
- (단점) SecurityContext / RequestContext 는 미적용 — 후속 ADR.
- (단점) MDC 가 비어있는 caller (스케줄링 batch 등) 에서는 의미 없음 — outbox relay 등은
  자체적으로 MDC 를 set 한 뒤 submit 하는 패턴 검토 필요.

## 후속 후보

- `SecurityContextPropagator` — Spring Security 의 인증 컨텍스트도 worker 까지. 권한 누수 검증
  필요.
- `TenantContextPropagator` — multi-tenancy (ADR-0017) 의 customer-id thread context.
- ScheduledTask / outbox relay 진입 시점에 MDC 를 명시적으로 set 하는 helper 추가 (서버 진입
  점에는 자동, batch 진입점에는 수동).
- Bulkhead worker pool 내부에서도 `Tracer.withSpan` 으로 child span 자동 생성 — 분산 추적의
  parent-child 관계 정확.
- Logback `MDCInsertingServletFilter` 에 customerId / requestId 추가 (현재 traceId / spanId 만).
