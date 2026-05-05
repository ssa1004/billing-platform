# ADR-0013: 월별 정산 동시성 — Postgres advisory lock

## 상태
적용

## 배경

월별 정산 batch 가 여러 인스턴스 (또는 운영자 수동 트리거) 에서 동시에 실행될 수 있음.
같은 customer × 같은 month 의 정산이 두 번 실행되면:

1. invoice 가 두 개 생성될 위험 (DB UNIQUE constraint 가 잡지만, 직전까지 두 트랜잭션이
   같이 진행되어 자원 낭비)
2. PG 결제 호출이 두 번 발생할 가능성 (멱등성 키가 invoice 단위이므로 invoice 가 두 개면
   결제도 두 번)
3. SettlementRun 통계가 꼬임

## 결정

`pg_advisory_xact_lock(hashtext("settlement:" + customerId + ":" + period))` 을
RunSettlementService 트랜잭션 시작 직후에 획득. 트랜잭션 종료 시 자동 해제.

```java
@Transactional
public SettlementResult run(RunSettlementCommand cmd) {
    String lockKey = "settlement:" + cmd.customerId().value() + ":" + cmd.period().toKey();
    advisoryLock.lock(lockKey);
    // ... 정산 처리
}
```

대안 검토:
- **Optimistic lock (`@Version`)**: invoice 가 두 번 생성되는 자체를 막을 수 없음 (unique
  constraint 에 걸리면 한쪽만 실패하고 자원은 이미 소비됨)
- **DB row lock (`SELECT ... FOR UPDATE`)**: 잠글 row 가 처음부터 없는 경우가 많음 (invoice
  가 아직 안 만들어진 상태에서 lock 잡아야 함)
- **Redis distributed lock**: 외부 의존성 추가. 정산이 high-frequency 가 아니므로 advisory
  lock 으로 충분
- **Application 단 ConcurrentHashMap**: 다중 인스턴스 (replica > 1) 에서 동작 안 함

## 결과

- 같은 customer × period 의 동시 정산이 직렬화됨
- 트랜잭션 종료 시 자동 해제 — 코드에서 unlock 신경 쓸 필요 없음
- key collision 위험은 해시 공간 (64bit) 대비 키 수 (수십만) 가 적어 무시 가능
- (주의) `lock()` 은 wait 모드. 다른 트랜잭션이 보유 중이면 대기. 정산 실행이 분 단위로
  걸릴 수 있어 수동 트리거 + batch 동시 실행 시 batch 가 길게 대기할 수 있음. 운영 모니터링
  시 lock wait time 메트릭 필요
- (대안) `tryLock()` 으로 즉시 실패 후 다음 batch 라운드에서 재시도하는 방법도 있음. 현재는
  단순성을 위해 wait 사용
