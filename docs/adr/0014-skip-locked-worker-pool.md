# ADR-0014: Worker pool 병렬 처리 — `FOR UPDATE SKIP LOCKED`

## 상태
적용

## 배경

월별 정산 batch 의 처리 대상 row (SettlementRun 또는 결제 재시도 대상 invoice) 를 여러
worker 가 나눠 처리해야 처리량을 확보할 수 있습니다. Worker pool 패턴.

대안:
- **Kafka partition**: 메시지 브로커가 한 단계 추가됨. 재처리 / 순서 보장이 복잡해짐
- **Worker 별 row 를 modulo (id 의 나머지 등) 로 분할**: worker 가 늘어나거나 줄어들 때
  재배치 비용이 큼
- **DB row lock (`SELECT ... FOR UPDATE`) + 단일 worker**: 직렬 처리라 처리량이 안 나옴
- **`SELECT ... FOR UPDATE SKIP LOCKED`**: PostgreSQL 9.5+. 다른 트랜잭션이 이미 잡은 row
  는 건너뛰고 자유로운 row 만 잡음

## 결정

### SKIP LOCKED 가 하는 일

`SELECT ... FOR UPDATE` 는 잡으려는 row 에 다른 트랜잭션의 lock 이 있으면 대기 합니다 →
워커가 줄 서서 직렬화. `SKIP LOCKED` 옵션을 더하면 이미 lock 잡힌 row 는 결과에서 제외하고
자유로운 row 만 가져옵니다. 즉 "다른 워커가 잡고 있는 건 건너뛰고 내가 잡을 수 있는 것만"
패턴 — DB 가 자동으로 row 를 워커들에게 분배.

### 적용

PENDING 상태의 SettlementRun 과 결제 재시도 대상 Invoice 모두 `SKIP LOCKED` 로 잡습니다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints({
    @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")  // SKIP_LOCKED
})
@Query("SELECT s FROM SettlementRunJpaEntity s WHERE s.status = :status ORDER BY s.createdAt")
List<SettlementRunJpaEntity> claimPendingForUpdate(...);
```

JPA 의 `lock.timeout = -2` 가 Postgres SQL 로는 `FOR UPDATE SKIP LOCKED` 로 변환됩니다 (JPA
spec 의 magic value).

실행 흐름:

1. Worker A 가 `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 50` → 50건 잡음
2. Worker B 가 동시에 같은 쿼리 → 다른 50건 잡음 (A 가 잡은 50건은 SKIP 으로 결과에서 제외)
3. Worker A/B 가 각자 chunk 처리 후 commit → lock 해제
4. 다음 chunk 에서 같은 패턴 반복 — sharding 키 / partition 사전 분할 없이 DB 가 알아서 분배

H2 (dev 용 in-memory DB) 는 SKIP LOCKED 를 미지원하므로 일반 `FOR UPDATE` 로 fallback (직렬
처리). 운영 PG 에서만 병렬 처리됩니다.

## 결과

- worker 수에 거의 선형으로 처리량 증가
- 운영 중 worker 추가 / 제거가 무중단으로 가능
- partition 같은 사전 분할 없음 — 모든 worker 가 동일한 query 를 날리고 DB 가 알아서 분할
- (한계) lock timeout 발생 가능 — chunk 처리가 너무 길면 다른 worker 가 대기. chunk 크기를
  50 정도로 작게 유지
- (한계) PostgreSQL 전용. MySQL 8.0+ 도 SKIP LOCKED 를 지원하지만 syntax 확인 필요
