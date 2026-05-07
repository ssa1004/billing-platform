# ADR-0022: Webhook 전송 시스템 (HMAC 서명 + retry/dead-letter + replay)

## 상태
적용

## 배경

B2B SaaS 빌링 시스템의 외부 통합 통로입니다. customer 가 우리 이벤트 (invoice 발행 /
payment 성공 / refund 처리 등) 를 *자기 서버에서 즉시 알고 싶을 때* 쓰는 방식이 webhook
(우리가 customer 서버 URL 로 HTTP POST 를 쏴서 알리는 push 통신) 입니다. PG 가 가맹점에
결제 결과를 알리는 그 매커니즘과 본질적으로 같습니다 — 우리는 발신자, customer 가 수신자.

요구사항:

1. **신뢰성** — customer 서버가 잠깐 다운돼도 결국 메시지가 도달해야 함
2. **진위 검증** — customer 가 "이 요청이 진짜 우리 billing 시스템에서 온 게 맞나?" 확인할 수
   있어야 함 (URL 을 알아낸 공격자가 가짜 webhook 을 보내는 시나리오 차단)
3. **운영 가시성** — 못 도달한 webhook 도 사라지지 않고 운영자가 보고 수동으로 재시도할 수
   있어야 함

## 결정

### 두 aggregate

```
WebhookEndpoint   ─┐  customer 의 등록 정보 (URL, secret, 구독 이벤트, 활성 상태)
                   │
WebhookDelivery   ─┘  한 이벤트 × 한 endpoint 의 전송 시도 (retry 라이프사이클)
```

한 이벤트가 발생하면, 구독한 endpoint 마다 *Delivery 1개씩* 만들어집니다. 각 delivery 는
독립적인 라이프사이클을 가집니다. 한 customer 의 endpoint A 가 다운돼도 endpoint B 로의
알림은 영향이 없습니다.

### HMAC 서명 (HMAC = Hash-based Message Authentication Code, 비밀 키와 메시지로 만든 위조
방지 서명)

- 알고리즘: `HMAC-SHA256(secret, "{timestamp}.{body}")` — Stripe / GitHub / Slack 표준 패턴
- 헤더: `X-Webhook-Signature: sha256=<hex>` (`sha256=` 접두사를 두면 나중에 SHA-512 /
  Ed25519 같은 다른 알고리즘으로 바꿀 때도 customer 검증 코드의 호환성을 유지)
- timestamp 를 같이 묶음 → 한 번 가로챈 요청을 그대로 다시 보내는 *재전송 (replay) 공격* 을
  막음 (customer 가 5분 이상 오래된 timestamp 는 거절하면 됨)
- secret 은 256비트 무작위 값. 등록 응답에 한 번만 평문으로 노출되고, 이후 조회는 hash 만
  반환. 분실 시 rotate-secret 으로 재발급.

### Retry 정책

총 5번 시도, exponential backoff (간격을 점점 늘리는 재시도): **1분 → 5분 → 30분 → 2시간
→ 12시간** (총 약 14시간 동안 시도).

- 5xx / timeout / network 오류 → 재시도 가능 (다음 시도까지 backoff 만큼 대기, PENDING 으로
  돌림)
- 408, 429 → 재시도 가능 (짧은 일시 장애)
- 나머지 4xx → dead (URL 자체가 잘못된 거라 재시도 무의미 → 즉시 DEAD_LETTERED)
- 5번 모두 재시도해도 실패 → DEAD_LETTERED

왜 1초마다 재시도하지 않는가: customer 서버가 다운된 동안 우리가 1초 간격으로 hit 하면
사실상 DDoS 가 됩니다. backoff 를 늘려가며 customer 에게 복구할 시간을 주고, 우리 큐도
막히지 않게 합니다.

### Dead letter (영구 실패 메시지 격리) + Replay (재발송)

5번 다 실패해도 row 는 DEAD_LETTERED 상태로 남습니다. 운영자 화면에서 보고 *수동 replay*
버튼으로 다시 보냅니다. 도메인의 `WebhookDelivery.replay()` 가 attemptCount 를 한도 미만으로
낮춰 추가 시도 1번을 보장합니다.

"customer 가 '아까 못 받았던 거 다시 보내달라' 라고 요청" 하는 시나리오의 정상 처리 통로.

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

- `FOR UPDATE SKIP LOCKED` — 여러 워커 인스턴스가 동시에 호출해도 같은 row 를 두 번 잡지
  않음. Postgres 의 `SKIP LOCKED` 가 이미 lock 잡힌 row 는 결과에서 제외해줍니다.
- 한 트랜잭션 안에서 발송 — IN_FLIGHT (보내는 중) 로 표시한 뒤 워커가 죽어 영영 IN_FLIGHT
  로 남는 시나리오를 회피. 단점은 HTTP 응답 대기로 트랜잭션이 길어진다는 것 (그래서 batch 5건
  + connect 5초 / read 10초 로 제한). 트래픽이 늘면 "claim 만 하는 트랜잭션 + send 는 별도"
  로 분리하고 IN_FLIGHT timeout 복구 batch 를 추가하는 방식으로 진화 가능.
- batch 5건 × 매 분 = 시간당 300건. 부족하면 인스턴스 수평 확장 (SKIP LOCKED 가 알아서
  분산해줌).

### 통합 통로 (이번 ADR 의 경계 밖)

`ScheduleWebhookUseCase` 가 *진입점* 입니다. 도메인 이벤트가 발생하는 곳에서 (또는 outbox
listener 가) 호출하면 customer 의 ACTIVE endpoint 들로 delivery 가 INSERT 됩니다. 본 ADR 은
*발송 시스템 자체* 의 설계만 다루며, outbox → schedule 연결은 후속 (Spring Modulith
`@ApplicationModuleListener` 등) 으로 둡니다.

## 대안 검토

- **Outbox 가 직접 HTTP 호출** — outbox relay loop 가 Kafka 대신 HTTP 도 함께 처리. 거부.
  retry / dead letter / customer 서버 응답 대기가 메인 outbox 에 영향을 주면서 책임 분리가
  깨짐.
- **Kafka 만 publish, customer 가 Kafka 컨슈머를 작성** — B2B 중에서도 가능한 customer 만
  대응 가능. 보통 customer 는 "REST 수신만 가능" 한 환경 (PHP/Rails legacy 등). HTTP push
  가 표준.
- **At-most-once (최대 한 번) + customer 알아서 polling** — customer 가 자기 missed event
  를 직접 폴링. 운영 부담을 customer 쪽에 떠넘김 — 좋은 DX 가 아님.

## 결과

- 외부 통합이 표준화 — 새 customer 온보딩이 "URL 등록 → 끝"
- HMAC 서명으로 진위 검증 가능
- retry + dead letter 로 일시 장애 자동 복구 + 영구 실패 가시화
- (단점) 도메인 이벤트 → schedule 연결이 별도 — outbox listener 를 따로 작성해야 함
- (단점) customer 측이 *멱등 처리 (같은 메시지를 두 번 받아도 결과가 같게 처리)* 를 못 하면
  retry 시 중복 처리 위험 — `Idempotency-Key` 헤더로 가이드를 주지만 customer 코드 품질에
  의존

## 후속 후보

- Outbox `@ApplicationModuleListener` 로 도메인 이벤트 → ScheduleWebhook 자동 연결
- IN_FLIGHT timeout 복구 batch (워커 비정상 종료 대비)
- Endpoint 별 *circuit breaker* — 한 customer URL 이 계속 실패하면 일정 시간 호출 중지
- Webhook 전용 metrics (성공률 / p99 latency / dead letter 발생률) Prometheus 노출
- Replay 시 attempt 한도 customer 가 정할 수 있게 (운영 화면 UX)
