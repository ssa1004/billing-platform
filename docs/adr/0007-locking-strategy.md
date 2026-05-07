# ADR-0007: 락 전략 — Wallet은 낙관적, race가 잦은 곳은 비관적

## 상태
적용

## 배경
Wallet 잔액 차감은 동시성 위험이 있습니다. 두 결제가 같은 wallet 을 동시에 차감하면 한쪽
쓰기가 다른 쪽 쓰기를 덮어쓰는 lost update 가 발생할 수 있습니다.

## 결정
**낙관적 락 (`@Version`)** (충돌이 드물다고 가정하고 일단 처리한 뒤, 충돌이 나면 예외 후
재시도하는 전략) 을 우선 사용합니다. JPA 가 UPDATE 시 `WHERE version = ?` 을 추가하므로,
다른 트랜잭션이 먼저 version 을 올렸다면 0 row 가 영향받고 OptimisticLockException 이 나서
클라이언트가 재시도합니다. 순간 부하 (burst rate) 가 낮은 결제 도메인에 적합합니다.

**race (경쟁 조건) 가 빈번한 곳** (예: 쿠폰 선착순) 은 PostgreSQL `pg_advisory_xact_lock`
(이름 붙인 임의의 잠금, 트랜잭션 끝나면 자동 해제) 또는 `SELECT FOR UPDATE` (조회한 row 에
다른 트랜잭션의 쓰기를 막는 락) 로 비관적 락 — 트랜잭션 단위로만 걸어줍니다.

## 결과
- 락 경합 없음 → 처리량 ↑
- 충돌 빈도가 낮으면 재시도 비용은 미미
- (단점) 충돌이 빈번하면 retry 폭주 (retry storm) — race 가 잦은 영역은 비관적 락으로 전환
- (단점) 클라이언트가 OptimisticLockException 처리 패턴을 알아야 함 (Spring Retry /
  Resilience4j Retry 로 자동화)
