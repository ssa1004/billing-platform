# ADR-0005: Outbox + Kafka DLQ로 이벤트 일관성 보장

## 상태
적용

## 배경
결제 성공 → 포인트 적립 / 알림 / 분석 컨슈머에게 이벤트를 발행해야 합니다. **Kafka 로 바로
produce** 할 때 발생하는 두 가지 실패 모드:
- DB commit 성공 → Kafka send 실패: 이벤트가 영구 유실
- Kafka send 성공 → DB rollback: phantom event (실제 존재하지 않는 거래에 대해 다운스트림이
  처리 시도)

## 결정
**Outbox 패턴** (이벤트를 일단 같은 트랜잭션 안에서 DB 의 outbox 테이블에 적어두고, 별도
워커가 그걸 읽어 메시지 브로커로 보내는 구조). 도메인 트랜잭션 안에서 `outbox` 테이블에
INSERT 합니다. Kafka publish 는 별도 `OutboxRelay` 가 polling 으로 처리합니다. 동기 send +
send 성공한 row 만 markPublished 처리하고, 실패한 row 는 다음 polling 에서 자동 재시도.

**컨슈머가 N 회 실패하면 DLQ** (Dead Letter Queue, 처리 실패한 메시지를 별도로 모아두는 큐)
로 보냅니다. Spring Kafka 의 `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`. 원본
topic 이름 + `.DLT` (Dead Letter Topic) suffix 로 보관. 운영자가 DLQ replay endpoint (DLQ
에서 메시지를 꺼내 다시 publish 하는 endpoint) 로 다시 발행할 수 있게 합니다.

## 결과
- 트랜잭션 일관성 (DB commit ↔ 이벤트 발행이 한 단위 atomic 으로 처리됨)
- Kafka 일시 장애 시 메시지 유실 0 건 (relay 가 재시도)
- DLQ 로 영구 실패 메시지를 격리 → 정상 컨슈머 처리가 막히지 않음
- (단점) at-least-once (최소 한 번 전달, 즉 중복 가능) → 컨슈머가 멱등성 (같은 메시지를 여러
  번 받아도 결과가 같음) 을 가정해야 함. eventId 기반 dedup (중복 제거) 으로 처리
- (단점) relay polling 부하 — 향후 부담스러우면 Debezium CDC (Change Data Capture, DB 변경
  로그를 그대로 스트림으로 흘려보내는 도구) 로 outbox 테이블을 source 삼아 변환 가능
