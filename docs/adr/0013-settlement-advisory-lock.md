# ADR-0013: 월별 정산 동시성 — Postgres advisory lock

## 상태
적용

## 배경

월별 정산 batch 가 여러 인스턴스에서 동시에 실행되거나 운영자가 수동 트리거를 같이 누를 수
있습니다. 같은 customer × 같은 month 의 정산이 두 번 실행되면:

1. invoice 가 두 개 생성될 위험 (DB UNIQUE 제약이 결국 잡지만, 그 직전까지 두 트랜잭션이
   같이 진행되어 자원이 낭비됨)
2. PG 결제 호출이 두 번 발생할 가능성 (멱등성 키가 invoice 단위라 invoice 가 두 개면 결제도
   두 번)
3. SettlementRun 통계가 꼬임

## 결정

### advisory lock 이 뭔가

Postgres 의 이름 기반 잠금. 일반 row lock 은 row 가 존재해야 잡을 수 있는데, advisory lock
은 row 가 아니라 임의의 64-bit 키 (정수) 에 lock 을 겁니다. "이 키로 들어온 트랜잭션은 한
번에 하나만" 같은 용도. 키 의미는 우리가 자유롭게 정함 — 여기선 `(customerId, period)` 조합.
`_xact_` 변종은 트랜잭션이 끝나면 자동 해제되어 unlock 코드를 따로 안 넣어도 됨.

### 적용 방식

`pg_advisory_xact_lock(hashtext("settlement:" + customerId + ":" + period))` 를
RunSettlementService 트랜잭션 시작 직후에 획득합니다. 같은 customer × period 의 정산이 다른
인스턴스에서 이미 진행 중이면 lock 이 풀릴 때까지 대기 → 결과적으로 직렬화.

```java
@Transactional
public SettlementResult run(RunSettlementCommand cmd) {
    String lockKey = "settlement:" + cmd.customerId().value() + ":" + cmd.period().toKey();
    advisoryLock.lock(lockKey);
    // ... 정산 처리
}
```

대안 검토:
- **낙관적 락 (`@Version`)**: invoice 가 두 번 생성되는 자체는 막을 수 없음 (unique 제약에
  걸리면 한쪽만 실패하지만, 그 사이 자원은 이미 소비됨)
- **DB row lock (`SELECT ... FOR UPDATE`)**: 잠글 row 가 처음부터 없는 경우가 많음 (invoice
  가 아직 안 만들어진 상태에서 lock 을 잡아야 함)
- **Redis 분산 락**: 외부 의존성 추가. 정산은 호출 빈도가 높지 않아 advisory lock 으로 충분
- **Application 단 ConcurrentHashMap**: 다중 인스턴스 (replica > 1) 에서는 동작 안 함

## 결과

- 같은 customer × period 의 동시 정산이 한 번에 하나씩만 진행 (직렬화)
- 트랜잭션 종료 시 자동 해제 — 코드에서 unlock 을 신경 쓸 필요 없음
- key 충돌 위험은 해시 공간 (64bit) 대비 키 수 (수십만) 가 적어 무시 가능
- (주의) `lock()` 은 wait 모드 — 다른 트랜잭션이 lock 을 들고 있으면 대기합니다. 정산 실행이
  분 단위로 걸릴 수 있어, 수동 트리거 + batch 가 동시에 실행되면 batch 가 길게 대기할 수
  있음. 운영 모니터링 시 lock 대기 시간 (lock wait time) 메트릭이 필요
- (대안) `tryLock()` 으로 즉시 실패 후 다음 batch 라운드에서 재시도하는 방법도 있음. 현재는
  단순성을 위해 wait 모드 사용
