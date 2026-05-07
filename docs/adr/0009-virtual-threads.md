# ADR-0009: Java 21 Virtual Threads

## 상태
적용

## 배경
결제 흐름은 blocking I/O fan-out (한 요청에서 외부 호출 여러 개를 차례로 또는 병렬로 호출)
이 많습니다 — DB UPDATE + PG REST 호출 + Outbox INSERT + Kafka publish. 전통적 platform
thread (OS 스레드 1:1) 모델은 (a) 동시 요청 = thread 1:1, (b) Tomcat max-threads 200 한계,
(c) thread 생성/스케줄링 비용이 듭니다.

WebFlux 같은 reactive 도입은 학습곡선이 크고 + 디버깅이 어렵고 + JPA 와 잘 맞지 않습니다.

## 결정
**Java 21 Virtual Threads (Project Loom)** (OS 스레드보다 훨씬 가벼운 가상 스레드, blocking
호출 시 자동으로 carrier 스레드를 양보). Spring Boot 3.2+ 한 줄 설정:
```yaml
spring:
  threads:
    virtual:
      enabled: true
```

이걸로 Tomcat / `@Async` / Spring Batch 의 워커 스레드가 가상스레드를 사용합니다. 기존 동기
blocking 코드를 그대로 두면서 처리량을 끌어올립니다.

## 결과
- 동시 요청 수십만까지 가능 (가상스레드는 단순 데이터 구조)
- 코드는 동기 그대로 — JPA / 디버거 / stack trace 그대로
- WebFlux 의 backpressure / 학습곡선 회피
- (단점) `synchronized` 블록은 carrier thread pinning (가상 스레드가 carrier 스레드에 묶여
  양보를 못 하는 현상) 발생 — `ReentrantLock` 으로 교체 권장 (현재 코드엔 없음)
- (단점) ThreadLocal 을 무겁게 쓰는 코드는 메모리 ↑ — MDC (로그 컨텍스트 저장용 ThreadLocal)
  정도는 OK
