# ADR-0008: Resilience4j Circuit Breaker for PG

## 상태
적용

## 배경
PG 사 (외부 결제 게이트웨이) 호출은 (a) 평균 200ms, (b) 가끔 timeout, (c) 드물게 장애로 5초+. 장애 시 우리 트랜잭션도 5초 lock — DB connection pool 고갈 위험.

## 결정
**Resilience4j Circuit Breaker** + **Retry** 조합.
- CB: `slidingWindow=20, failureRate=50%, waitInOpen=30s` — 실패율 임계 초과 시 30초 OPEN (즉시 fallback)
- Retry: 3회, exponential backoff 200ms → 400ms → 800ms — transient 장애 흡수
- Fallback: `AuthorizeResult.rejected("CB_OPEN", ...)` — 사용자에겐 즉시 거절 응답 (5초 대기 X)

```java
@CircuitBreaker(name = "pg", fallbackMethod = "authorizeFallback")
@Retry(name = "pg")
public AuthorizeResult authorize(AuthorizeRequest req) { ... }
```

## 결과
- PG 장애가 우리 시스템 전체로 전파되지 않음 (격리)
- DB connection pool 보호
- Hystrix 후속 — 활발히 유지 보수 중
- (단점) false positive (transient 였는데 CB OPEN) 시 사용자 거절 늘 수 있음 — 임계 튜닝 필요
