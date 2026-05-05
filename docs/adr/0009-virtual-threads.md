# ADR-0009: Java 21 Virtual Threads

## 상태
적용

## 배경
결제 흐름은 blocking I/O fan-out 이 많음 — DB UPDATE + PG REST 호출 + Outbox INSERT + Kafka publish. 전통적 platform thread 모델은 (a) 동시 요청 = thread 1:1, (b) Tomcat max-threads 200 한계, (c) thread 생성/스케줄링 cost.

WebFlux 같은 reactive 도입은 학습곡선 큼 + 디버깅 어려움 + JPA 와 안 맞음.

## 결정
**Java 21 Virtual Threads (Loom).** Spring Boot 3.2+ 한 줄 설정:
```yaml
spring:
  threads:
    virtual:
      enabled: true
```

이걸로 Tomcat / `@Async` / Spring Batch 의 워커 스레드가 가상스레드 사용. 기존 동기 blocking 코드 그대로 두면서 처리량 ↑.

## 결과
- 동시 요청 수십만까지 가능 (가상스레드는 단순 데이터 구조)
- 코드는 동기 — JPA / 디버거 / stack trace 그대로
- WebFlux 의 backpressure / 학습곡선 회피
- (단점) `synchronized` 블록은 carrier thread pinning — `ReentrantLock` 으로 교체 권장 (현재 코드엔 없음)
- (단점) ThreadLocal heavy 코드는 메모리 ↑ — MDC 정도는 OK
