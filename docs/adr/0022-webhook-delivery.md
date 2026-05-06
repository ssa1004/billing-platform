# ADR-0022: Webhook 전송 시스템 (HMAC 서명 + retry/dead-letter + replay)

## 상태
적용

## 배경

B2B SaaS 빌링 시스템의 외부 통합 통로. customer 가 우리 이벤트 (invoice 발행 / payment 성공 /
refund 처리 등) 를 *자기 서버에서 즉시 알고 싶을 때* 쓴다. PG (Toss / Stripe / PayPal) 가
가맹점에 결제 결과를 알리는 그 매커니즘과 본질적으로 같다 — 우리는 발신자, customer 가 수신자.

요구사항:

1. **신뢰성** — customer 서버가 잠깐 다운돼도 결국 메시지가 도달해야 한다.
2. **진위 검증** — customer 가 "이 요청이 진짜 우리 billing 시스템에서 온 게 맞나?" 확인할 수
   있어야 한다 (URL 알아낸 공격자가 가짜 webhook 보내는 시나리오 차단).
3. **운영 가시성** — 못 도달한 webhook 도 사라지지 않고 운영자가 보고 수동 재시도할 수 있어야 한다.

## 결정

### 두 aggregate

```
WebhookEndpoint   ─┐  customer 의 등록 정보 (URL, secret, 구독 이벤트, 활성 상태)
                   │
WebhookDelivery   ─┘  한 이벤트 × 한 endpoint 의 전송 시도 (retry 라이프사이클)
```

한 이벤트 발생 시 — 구독한 endpoint 마다 *Delivery 1개씩* 만들어진다. 각 delivery 는
독립 라이프사이클. 한 customer 의 endpoint A 가 다운돼도 endpoint B 로의 알림은 영향 X.

### HMAC 서명

- 알고리즘: `HMAC-SHA256(secret, "{timestamp}.{body}")` — Stripe / GitHub / Slack 표준 패턴
- 헤더: `X-Webhook-Signature: sha256=<hex>` (algo prefix → 나중에 SHA-512 / Ed25519 갈아탈 때 backward compat)
- timestamp 같이 묶음 → *replay 공격* 방지 (customer 가 5분 이상 오래된 timestamp 거절하면 됨)
- secret 은 256-bit 무작위. 등록 응답 1번만 평문 노출, 이후 조회는 hash 만. 분실 시 rotate-secret
  으로 재발급.

### Retry 정책

5번 시도, exponential backoff: **1m → 5m → 30m → 2h → 12h** (총 ~14h 동안 시도).

- 5xx / timeout / network → retryable (다음 시도까지 backoff 만큼 대기, PENDING 으로 돌림)
- 408, 429 → retryable (짧은 일시 장애)
- 나머지 4xx → dead (URL 자체가 잘못된 거라 재시도 무의미 → 즉시 DEAD_LETTERED)
- 5번 모두 retryable 실패 → DEAD_LETTERED

왜 1초마다 재시도 안 함: customer 서버가 다운된 동안 우리가 1초 간격으로 hit 하면 사실상 DDoS.
backoff 늦춰가며 customer 가 복구할 시간 + 우리도 큐 안 막힘.

### Dead letter + Replay

5번 다 실패해도 row 는 DEAD_LETTERED 로 남는다. 운영자 화면에서 보고 *수동 replay* 버튼.
도메인의 `WebhookDelivery.replay()` 가 attemptCount 를 한도 미만으로 낮춰 1번 추가 시도 보장.

"customer 가 '아까 못 받았던 거 다시 보내달라' 요청" 시나리오의 정상 처리 통로.

### Worker 패턴

```
@Scheduled(매 분)  →  Spring Batch tasklet
                       ↓
                     deliverBatch(5)        # 5건씩 잡음
                       ↓
                     [한 트랜잭션]
                       claimPending()   ← FOR UPDATE SKIP LOCKED
                       for each:
                         beginAttempt()   # IN_FLIGHT
                         httpClient.send()
                         markSuccess / markRetryable / markDead
                         save
                       commit
```

- `FOR UPDATE SKIP LOCKED` — 여러 워커 인스턴스가 동시에 호출해도 같은 row 두 번 안 잡음.
  Postgres 의 `SKIP LOCKED` 가 이미 lock 잡힌 row 는 결과에서 제외.
- 한 트랜잭션 안에 발송 — IN_FLIGHT 후 워커 죽음 → 영영 IN_FLIGHT 시나리오 회피. 단점은
  HTTP 응답 대기로 트랜잭션이 길어지는 것 (그래서 batch 5건 + connect 5초 / read 10초).
  트래픽 늘면 "claim 만 한 트랜잭션 + send 별도" 로 분리 + IN_FLIGHT timeout 복구 batch 추가.
- batch 5건 × 매 분 = 시간당 300건. 부족하면 인스턴스 수평 확장 (SKIP LOCKED 가 알아서 분산).

### 통합 통로 (이번 ADR 의 경계 밖)

`ScheduleWebhookUseCase` 가 *진입점* — 도메인 이벤트가 발생하는 곳에서 (또는 outbox listener
가) 호출하면 customer 의 ACTIVE endpoint 들로 delivery 가 INSERT. 본 ADR 은 *발송 시스템 자체*
의 설계만 — outbox → schedule 연결은 후속 (Spring Modulith `@ApplicationModuleListener` 등).

## 대안 검토

- **Outbox 가 직접 HTTP 호출** — outbox relay loop 가 Kafka 대신 HTTP 도 함께 처리. 거부.
  retry / dead letter / customer 서버 응답 대기 시 메인 outbox 까지 영향 → 책임 분리가 깨짐.
- **Kafka 만 publish, customer 가 Kafka consumer 작성** — B2B 가 가능한 customer 만. 보통
  customer 는 "REST 수신만 가능" 한 환경 (PHP/Rails legacy). HTTP push 가 표준.
- **At-most-once + customer 알아서 polling** — customer 가 자기 missed event 직접 폴링.
  운영 부담 customer 측에 떠넘김 — 좋은 DX 아님.

## 결과

- 외부 통합이 표준화 — 새 customer 온보딩이 "URL 등록 → 끝"
- HMAC 서명으로 진위 검증 가능
- retry + dead letter 로 일시 장애 자동 복구 + 영구 실패 가시화
- (단점) 도메인 이벤트 → schedule 연결이 별도 — outbox listener 작성 필요
- (단점) customer 측이 *멱등 처리* 못 하면 retry 시 중복 처리 위험 — `Idempotency-Key` 헤더로
  가이드는 하지만 customer 코드 품질에 의존

## 후속 후보

- Outbox `@ApplicationModuleListener` 로 도메인 이벤트 → ScheduleWebhook 자동 연결
- IN_FLIGHT timeout 복구 batch (워커 비정상 종료 대비)
- Endpoint 별 *circuit breaker* — 한 customer URL 이 계속 실패하면 일정 시간 호출 중지
- Webhook 전용 metrics (성공률 / p99 latency / dead letter 발생률) Prometheus 노출
- Replay 시 attempt 한도 customer 가 정할 수 있게 (운영 화면 UX)
