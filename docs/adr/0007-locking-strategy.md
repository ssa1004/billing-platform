# ADR-0007: 락 전략 — Wallet은 낙관적, race가 잦은 곳은 비관적

## 상태
적용

## 배경
Wallet 잔액 차감은 동시성 위험. 두 결제가 같은 wallet 을 동시에 차감하면 lost update 가능.

## 결정
**낙관적 락 (`@Version`)** 우선. JPA 가 UPDATE 시 `WHERE version = ?` 추가 → 충돌 시 OptimisticLockException → 클라이언트 retry. burst rate 가 낮은 결제 도메인에 적합.

**race 빈번한 곳** (예: 쿠폰 선착순) 은 PostgreSQL `pg_advisory_xact_lock` 또는 `SELECT FOR UPDATE` 로 비관적 락 — 트랜잭션 단위로만.

## 결과
- 락 경합 없음 → 처리량 ↑
- 충돌 빈도 낮으면 retry cost 미미
- (단점) 충돌 빈번하면 retry storm — race-prone 영역은 비관적으로 전환
- (단점) 클라이언트가 OptimisticLockException 처리 패턴 알아야 함 (Spring Retry / Resilience4j Retry 로 자동화)
