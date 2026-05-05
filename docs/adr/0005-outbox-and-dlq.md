# ADR-0005: Outbox + Kafka DLQ로 이벤트 일관성 보장

## 상태
적용

## 배경
결제 성공 → 포인트 적립 / 알림 / 분석 컨슈머에게 이벤트 발행 필요. **직접 Kafka produce** 의 두 실패 모드:
- DB commit 성공 → Kafka send 실패: 이벤트 영구 유실
- Kafka send 성공 → DB rollback: phantom event (없는 거래 처리 시도)

## 결정
**Outbox 패턴.** 도메인 트랜잭션 안에서 `outbox` 테이블에 INSERT — Kafka publish 는 별도 `OutboxRelay` 가 polling 으로 처리. 동기 send + send 성공 row 만 markPublished, 실패 row 는 다음 polling 에서 자동 재시도.

**컨슈머 N회 실패 → DLQ.** Spring Kafka `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`. 원본 topic + `.DLT` suffix 로 보관. 운영자가 DLQ replay endpoint 로 다시 publish.

## 결과
- 트랜잭션 일관성 (DB commit ↔ 이벤트 발행 atomic)
- Kafka 일시 장애 시 메시지 유실 0 (relay 가 retry)
- DLQ 로 영구 실패 메시지 격리 → 정상 컨슈머 처리 안 막힘
- (단점) at-least-once → 컨슈머 멱등성 가정 (eventId 기반 dedup)
- (단점) relay polling 부하 — 향후 부담스러우면 Debezium CDC 로 source-transform 가능 (outbox 테이블 그대로 source)
