# ADR-0008: Resilience4j Circuit Breaker for PG

## 상태
적용

## 배경
PG 사 (외부 결제 게이트웨이, Payment Gateway) 호출은 (a) 평균 200ms, (b) 가끔 timeout, (c)
드물게 장애로 5초 이상 걸립니다. 장애가 길어지면 우리 트랜잭션도 5초간 락에 묶여 DB
connection pool (DB 연결 풀) 이 고갈될 위험이 있습니다.

## 결정
**Resilience4j Circuit Breaker** (서킷 브레이커, 실패가 누적되면 호출 자체를 잠시 차단해서
자원을 보호하는 장치) + **Retry** (재시도) 조합.
- CB: `slidingWindow=20, failureRate=50%, waitInOpen=30s` — 최근 20건 중 실패율이 임계
  (50%) 를 넘으면 30초 동안 OPEN 상태로 두고 호출을 차단 (즉시 fallback 으로)
- Retry: 3회, exponential backoff (간격을 두 배씩 늘리는 재시도) 200ms → 400ms → 800ms —
  일시적 (transient) 장애 흡수용
- Fallback: `AuthorizeResult.rejected("CB_OPEN", ...)` — 사용자에겐 즉시 거절 응답을 돌려줌
  (5초 대기 X)

```java
@CircuitBreaker(name = "pg", fallbackMethod = "authorizeFallback")
@Retry(name = "pg")
public AuthorizeResult authorize(AuthorizeRequest req) { ... }
```

## 결과
- PG 장애가 우리 시스템 전체로 전파되지 않음 (격리)
- DB connection pool 보호
- 단종된 Hystrix 의 후속 라이브러리 — 활발히 유지 보수 중
- (단점) false positive (실제론 일시 장애였는데 CB 가 OPEN 됨) 시 사용자 거절이 늘 수 있음
  — 임계 튜닝 필요
