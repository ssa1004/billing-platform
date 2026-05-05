# ADR-0003: 애그리거트 경계 — Wallet / Order / Payment 분리

## 상태
적용

## 배경
*"Order, Payment, Wallet, Refund 를 모두 하나의 aggregate 로 묶을 것인가?"* — 한 결제는 4개를 모두 건드리니 자연스러운 유혹. 하지만 한 aggregate 로 묶으면 (a) 트랜잭션 락 범위가 폭발하고 (b) 동시성 처리가 어려움.

## 결정
**도메인 객체별로 aggregate root 를 따로**. Order, Payment, Refund, Wallet 4개. 각자 자신의 invariant 만 책임.

```
Order        → status state machine, total = sum(item)
Payment      → PG 결과 보존, idempotencyKey unique
Refund       → REQUESTED → APPROVED → COMPLETED
Wallet       → balance >= 0, blocked <= balance
LedgerEntry  → append-only VO (aggregate 아님)
```

aggregate 간 관계는 **ID 참조** (Order.paymentId 등). 직접 객체 참조 금지. 트랜잭션 한 번에 두 aggregate 변경 시 application service 가 명시적으로 두 번 save (eventual consistency 거나 같은 트랜잭션).

## 결과
- 각 aggregate 의 락 범위 작음 → 동시성 ↑
- 트랜잭션 한 번에 변경되는 aggregate 가 명시적 (코드 리뷰 시 잘 보임)
- 개별 invariant 가 도메인 메서드 안에 응집 (setter 노출 0)
- (단점) Order 와 Payment 일관성은 Saga / Outbox 에 의존 (즉시 일관성 X)
- (단점) ID 참조라 join 시 N+1 주의 — Read 쪽은 CQRS 로 별도 처리 (ADR-0004)
