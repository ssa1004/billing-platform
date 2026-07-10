# ADR-0033: DLQ 관리 콘솔 백엔드 API

## 상태
적용. ADR-0005 (Outbox + Kafka DLQ) 와 ADR-0030 (회계/결제 row soft delete) 의 운영 보조.
기존 `/admin/dlq/replay` 단건 endpoint 는 호환 보존.

## 배경

billing 의 outbox + consumer 흐름은 ADR-0005 로 정착 — domain transaction 안에서 `outbox`
테이블에 INSERT, OutboxRelay 가 polling 해 Kafka 로 publish, 컨슈머가 N회 실패하면 `.DLT`
(Dead Letter Topic) 로 격리. 운영 중 굳어진 4가지 DLQ 카테고리:

- **payment failure** (`billing.payment.*`) — PG authorize / capture 실패 후 처리 컨슈머 실패
- **refund failure** (`billing.refund.*`) — PG 환불 호출 실패, dedup 실패
- **settlement failure** (`billing.settlement.*`) — 정산 per-row 처리 실패 (대량 batch)
- **PG webhook failure** (`billing.pg-webhook.*`) — webhook 수신 후 idempotency 미스 등

기존 `DlqAdminController` (`POST /admin/dlq/replay?dltTopic=...`) 는 단건 / topic 전체 replay
만 지원 — 운영 중 드러난 한계:

- **필터 부재** — DLT topic 전체를 한꺼번에 또는 topic 이름으로만 좁힐 수 있음. customer /
  errorClass / 시간 범위 / 한 메시지 단위 분리가 안 됨.
- **단건 detail 부재** — 메시지 헤더 / payload / stacktrace / 원본 consumer group 정보가 안
  보임. 운영자가 문제 메시지를 골라 사고 재현을 하기 어려움.
- **bulk 안전망 부재** — replay 한 번에 topic 의 모든 메시지를 재발행 — 의도치 않은 재청구
  사고 위험 (돈 직결).
- **stats 부재** — "어느 source 가, 어느 시간대에, 어느 customer 의 결제가 가장 많이 실패
  했나" 를 시스템에서 답해주는 화면이 없음. 운영자가 grep 으로 추적.

같은 시기 notification-hub 에서 ADR-0015 로 동일 패턴 (DLQ admin v2 — filter / detail / stats /
bulk) 을 정착시켜 **검증된 표준 패턴**. 도메인 의존 없는 부분 (rate-limit / audit / dry-run /
bulk worker / cursor pagination) 그대로 이식.

## 결정

기존 `/admin/dlq/replay` 는 호환 유지. `/api/v1/admin/dlq` 아래 8개 endpoint 신규 추가:

| HTTP | path | 동작 |
|---|---|---|
| `GET` | `/api/v1/admin/dlq` | filter + cursor 페이지네이션 (`source` / `topic` / `consumerGroup` / `from` / `to` / `errorType` / `cursor` / `size`) |
| `GET` | `/api/v1/admin/dlq/{messageId}` | 단건 detail — payload + headers + stacktrace + retry context |
| `POST` | `/api/v1/admin/dlq/{messageId}/replay` | 단건 replay (멱등 가드 — 2회차 409) |
| `POST` | `/api/v1/admin/dlq/{messageId}/discard` | 단건 discard (soft, `reason` 필수) |
| `POST` | `/api/v1/admin/dlq/bulk-replay` | filter 로 다건 replay — `confirm=true` 없으면 dry-run 강제 |
| `POST` | `/api/v1/admin/dlq/bulk-discard` | filter 로 다건 discard — `reason` 필수 |
| `GET` | `/api/v1/admin/dlq/bulk-jobs/{jobId}` | 비동기 bulk job 진행도 / 결과 |
| `GET` | `/api/v1/admin/dlq/stats` | 시간 bucket × source × errorClass × customer 집계 |
| `DELETE` | `/api/v1/admin/dlq/{messageId}` | 항상 405 — soft discard 만 허용 |

`messageId` 는 `<dltTopic>:<partition>:<offset>` 합성 문자열. Kafka 가 unique 보장하는 자연 키.

### billing 특유 — 돈 직결 안전망

- **bulk-replay dry-run 강제** — request body 의 `confirm` 이 명시적으로 `true` 가 아니면
  application service 단에서 응답을 `mode=DRY_RUN` 으로 강제. 대상 개수 추정 + sample
  messageId 10개만 반환. 운영자가 sample을 검토한 후 `confirm=true`로 재호출해야 실
  실행. 한 번에 수천 건의 재청구 사고 방지.
- **Idempotency-Key 복사** — `KafkaDlqMessageStore.replay` 가 원본 메시지의
  `Idempotency-Key` 헤더와 `customer-id` 헤더를 원본 topic 재발행 시 그대로 복사. 컨슈머가
  같은 키로 두 번째 도착을 dedup 가능 — 이중 결제 / 이중 환불 방지 (ADR-0006 / ADR-0028 의
  멱등성 정책과 연계).
- **PG webhook source 필터** — `source=PG_WEBHOOK` 으로 webhook idempotency 미스만 좁혀
  보기. operator 가 webhook 재발송 요청 전에 어떤 메시지가 stuck 인지 분리.
- **byCustomer 통계 차원** — `DlqStats.byCustomer` 로 같은 고객의 여러 결제가 한꺼번에 실패
  하는 패턴 (카드 한도 초과 등) 을 한눈에 감지. notification-hub 에는 없는 billing 특유.
- **hard DELETE 차단** — `DELETE /{messageId}` 는 항상 405. discard 만 허용 (soft,
  ADR-0030 의 회계 row 보존 원칙과 일관).

### 권한 / 안전

- **권한**: `AdminDlqController` class 단위 `@PreAuthorize("hasRole('admin')")`. JWT subject 가
  audit actor (`AuditActor.Type.OPERATOR`) 의 id. 익명 환경 (`PermissiveSecurityConfig`,
  ADR-0031 의 dev fallback) 은 `anonymous` 로 폴백 — 운영 환경에서는 JWT 가 활성이어야 의미
  있는 audit 가 남음.
- **rate limit**: 호출자 IP × scope (`admin:dlq:read` / `admin:dlq:write` / `admin:dlq:bulk`)
  별 token bucket (Redis Lua, 분당 60). 초과 시 429 + `Retry-After`. notification-hub ADR-0015
  의 같은 패턴 — namespace 만 분리 (`billing:` vs `notif:`).
- **audit**: 모든 write endpoint 가 `AuditAction.DLQ_*` 8종 (REPLAY / DISCARD / BULK_REPLAY_*
  3종 / BULK_DISCARD_* 3종) 발행 — actor / targetId / customerId / reason / 결과 / 원본
  topic 기록. 분쟁 발생 시 어떤 운영자가 어떤 고객의 어떤 메시지를 재발행했는지 즉답.
- **멱등성**: 단건 replay / discard 두 번째 호출은 어댑터 단 dedup marker 로
  `IllegalDlqOperationException` → 409 `ILLEGAL_DLQ_OPERATION`.

### 비동기 bulk worker

- `dlqBulkExecutor` (`ThreadPoolTaskExecutor` core 1 / max 2 / queue 8) — 동시 bulk 실행 1건만
  허용. PG / Kafka / Outbox 폭주 방지. concurrency 늘릴 필요가 있으면 cluster 단에서 다른
  pod 가 받게 두는 방향.
- 각 메시지는 `TransactionTemplate.execute` 로 별도 트랜잭션 — 한 건 실패가 다른 건 롤백을
  일으키지 않음 → partial failure 추적 가능.
- 결과 보존 — `InMemoryDlqBulkJobRepository` (1시간 retention lazy GC). 노드 재시작 시 진행
  중 job 정보 손실 — DB / Redis 어댑터 추가는 port 교체로 충분.

### Kafka DLT 접근 방식

billing 은 별도 DB schema 변경 없이 도입 — 호출 시점에 운영자 filter 의 topic prefix 와 일치
하는 `.DLT` topic 들을 `KafkaConsumer` 로 단발 poll → 메모리에서 필터 / cursor / 통계 처리.
notification-hub 의 DB 기반 EXHAUSTED 와 다른 점.

장점:
- 외부 schema 변경 0 — 신규 endpoint 만 추가 (제약 준수).
- Kafka 가 자연스럽게 retention 후 DLT 메시지 정리 — 별도 cleanup 잡 불필요.

단점:
- DLQ 가 수천 건 넘어가면 응답 시간 길어짐. `billing.dlq.admin.scan-max-records` (기본
  1000) 로 상한, 그 이상은 운영자가 filter 좁히도록 강제.
- in-memory dedup marker → 노드 재시작 시 손실 → 동일 메시지가 두 번 처리될 수 있음.
  컨슈머의 멱등성 (Idempotency-Key dedup) 으로 1차 방어, marker 로 2차 방어.

### stats 구현

`KafkaDlqMessageStore.aggregateStats` 가 raw `StatsRow` (bucketStart, source, errorClass,
customerId, count) 만 반환. `DlqAdminService.stats` 가 그 row 를 차원별 (bucket / source /
errorClass / customer) 로 합계. 어댑터는 group by 책임만, 차원 추가/제거는 use case 에서 —
notification-hub ADR-0015 와 같은 분리 원칙.

bucket 은 ISO-8601 Duration (`PT1H` / `PT15M`). null 이면 1시간. from / to null 이면 최근 24h.

## 결과

- **운영자가 안전하게 DLQ 대량 처리 가능** — dry-run 으로 sample 확인 후 confirm. 의도치
  않은 재청구 / 재정산 사고 방지.
- **partial failure 추적** — bulk job 의 `successCount` / `failureCount` / `firstError` 로 부분
  실패 대응. 한 건 실패가 다른 건을 롤백하지 않음.
- **customer 단위 패턴 감지** — `byCustomer` stats 로 "한 고객의 결제가 한꺼번에 stuck" 같은
  도메인 alarm 의 데이터 출처.
- **확산 가능한 표준** — notification-hub ADR-0015 의 동일 패턴. 다른 서비스 (commerce-ops /
  catalog) 에도 어댑터만 교체로 적용 가능. 도메인 의존 없는 부분 (rate-limit / audit /
  dry-run / bulk worker / cursor) 은 그대로 옮길 수 있음.
- (단점) **in-memory job 저장** — 노드 재시작 시 진행 중 job 정보 소실. 단건 처리 자체는
  컨슈머 단에 영속화되므로 데이터 정합은 깨지지 않음 — 운영자가 정확한 진행도 추적이 안 될
  뿐. DB / Redis 어댑터 추가는 후속 작업.
- (단점) **in-memory dedup marker** — 노드 재시작 시 같은 메시지가 다시 list 에 노출. 컨슈머의
  Idempotency-Key dedup 이 1차 방어선 (이중 결제 방지).
- (단점) **Kafka 단발 poll 의 스케일 한계** — DLQ 가 일평균 만 건 넘어가면 응답 시간 폭증.
  DB mirror 도입 시점 명시.

## 용어 풀이 (쉽게)

- **DLQ / DLT (실패 메시지 격리함)** — 아무리 재시도해도 처리 안 되는 문제 메시지를 따로 모아두는 보관함(반송 우편함). 정상 줄을 막지 않게 빼두고, 운영자가 나중에 재발송(replay)하거나 폐기(discard)한다.
- **cursor pagination (커서 페이지네이션)** — '몇 페이지째'가 아니라 '직전에 본 마지막 항목 다음부터' 가져오는 목록 넘기기. 책갈피를 꽂아 거기서부터 이어 읽어, 데이터가 계속 쌓여도 누락·중복이 없다.
- **token bucket rate limit (토큰 양동이 호출 제한)** — 호출 권한을 '토큰'으로 보고, 일정 속도로 채워지는 양동이에서 호출 1건이 1개를 꺼내 쓰는 방식. 비면 잠시 거절(429)해 운영자의 폭주 클릭이 시스템을 때리지 못하게 한다.
- **dry-run (예행연습)** — 진짜 실행하지 않고 "이렇게 하면 몇 건이 어떻게 된다"는 미리보기만 주는 안전모드. `confirm=true`를 붙여야 실제 재발송이 돌아가, 한 번의 실수로 수천 건이 이중 청구되는 사고를 막는다.
- **partial failure isolation (부분 실패 격리)** — 대량 작업에서 한 건이 실패해도 그 한 건만 실패로 두고 나머지는 계속 진행하는 것. 각 메시지를 별도 트랜잭션으로 처리해, 한 건의 롤백이 옆 건을 끌고 넘어지지 않게 한다.

## 트레이드오프

### "왜 notification-hub 처럼 DB mirror 가 아닌가"

notification-hub 의 EXHAUSTED 는 도메인 모델 (`DeliveryAttempt.status`) 의 일부 — DB 가 자연
스러운 저장소. billing 의 DLQ 메시지는 Kafka 만의 객체 — DB mirror 를 만들려면 schema 추가
필요 (제약 위배: DB schema 변경 X). 운영 부담 측면도 outbox 테이블 외에 별도 DLQ mirror 까지
관리 = 단순한 시작 원칙 (YAGNI) 위반.

### "왜 단건 endpoint 만으로 안 되는가"

bulk 가 무거운 dry-run / 비동기 worker / job repository 등 의존성이 늘어남에도 분리 가치:
- **단건 호출자 (운영 스크립트)** 가 bulk 의존성 없이 단순히 사용 가능.
- bulk 의 실수 (의도치 않은 confirm=true) 가 단건 흐름까지 영향 안 줌 (failure isolation).
- 추후 bulk 만 별도 서비스 / 별도 큐 라우팅으로 옮기는 옵션 (확장성).

### "왜 Spring Security 의 hasRole 만으로 충분한가"

notification-hub ADR-0015 는 자체 `AdminAuthFilter` + `AdminContext` 사용 (Spring Security
미도입 결정). billing 은 이미 Spring Security + OIDC (ADR-0031) 인프라가 있어 `@PreAuthorize`
하나로 충분. 별도 filter / context 도입은 중복. JWT subject 가 audit actor id 로 직접 매핑.

### "왜 Kafka admin client 가 아니라 KafkaConsumer 로 .DLT 를 읽는가"

`AdminClient` 는 topic 메타데이터 (생성 / 삭제 / 설정) 용 — 메시지 자체는 못 읽음.
KafkaConsumer 가 메시지 read 의 정공법. unique group.id 로 매 호출마다 새 consumer →
offset commit 영향 X (다른 컨슈머의 진행에 간섭 안 함).

## 다시 검토할 시점

- **DLQ 일평균 1만 건 넘으면** — Kafka 단발 poll 의 응답 시간 / 메모리 압박이 운영 SLO 를
  깨면 DB mirror (별도 `dlq_messages` 테이블) 로 전환. KafkaListener 가 DLT 컨슈머로 mirror
  insert 하면 그 뒤는 notification-hub 와 같은 구조.
- **bulk job in-memory 손실로 운영 혼선이 한 번이라도 발생** — Redis / DB 어댑터로 즉시 이전.
  `DlqBulkJobRepository` port 만 갈아끼우면 됨.
- **단건 marker 의 dedup 강도가 필요** — Redis 어댑터로 marker 영속화 (cluster 차원 dedup).
- **byCustomer stats 가 운영 도구로 굳어지면** — Kafka header 의 `customer-id` 강제화. 현재는
  ProcessPaymentService / RefundService 가 헤더로 박지만 SettlementService 는 row 단위라
  미부착 — 일관 적용 검토.

## cross-reference

- notification-hub ADR-0015 — 같은 패턴의 원형 (DLQ admin v2). 도메인 의존 없는 부분
  (rate-limit / audit / dry-run / bulk worker / cursor pagination / stats 후처리) 그대로 이식.
- billing ADR-0005 — Outbox + Kafka DLQ 의 기반. 이 ADR 의 운영 보조.
- billing ADR-0006 / ADR-0028 — Idempotency-Key 기반 dedup. replay 의 안전성 1차 방어.
- billing ADR-0023 — Audit log. DLQ_* 행위가 같은 테이블로 기록됨.
- billing ADR-0030 — soft delete. discard 의 hard DELETE 차단 원칙과 일관.
- billing ADR-0031 — API versioning. v1 path 컨벤션 (`/api/v1/admin/dlq`) 따름.
