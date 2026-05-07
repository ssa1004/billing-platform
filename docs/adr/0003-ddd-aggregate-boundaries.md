# ADR-0003: 애그리거트 경계 — Wallet / Order / Payment 분리

## 상태
적용

## 배경
*"Order, Payment, Wallet, Refund 를 모두 하나의 aggregate (한 트랜잭션으로 같이 저장하는
도메인 객체 묶음, 일관성 단위) 로 묶을 것인가?"* — 한 결제는 4개를 모두 건드리니 자연스러운
유혹입니다. 하지만 한 aggregate 로 묶으면 (a) 트랜잭션 락 범위가 폭발하고 (b) 동시성 처리가
어려워집니다.

## 결정
**도메인 객체별로 aggregate root 를 따로** 둡니다. Order, Payment, Refund, Wallet 4개. 각자
자신의 invariant (불변 조건, 항상 지켜져야 하는 규칙) 만 책임집니다.

```
Order        → 상태 전이 (state machine), total = sum(item)
Payment      → PG 결과 보존, idempotencyKey unique
Refund       → REQUESTED → APPROVED → COMPLETED
Wallet       → balance >= 0, blocked <= balance
LedgerEntry  → append-only VO (한 번 기록되면 수정 안 함, aggregate 아님)
```

aggregate 간 관계는 **ID 참조** (Order.paymentId 등). 직접 객체 참조는 금지합니다. 트랜잭션
한 번에 두 aggregate 를 변경할 때는 application service 가 명시적으로 두 번 save 합니다
(시간이 지나면서 양쪽이 같아지는 eventual consistency 또는 같은 트랜잭션 안에서 처리).

## 결과
- 각 aggregate 의 락 범위가 작음 → 동시성 ↑
- 한 트랜잭션에서 변경되는 aggregate 가 명시적 (코드 리뷰 시 잘 보임)
- 개별 invariant 가 도메인 메서드 안에 응집 (setter 외부 노출 0)
- (단점) Order 와 Payment 의 일관성은 Saga (여러 단계로 나눠 보상 트랜잭션으로 롤백) /
  Outbox 에 의존 (즉시 일관성 X)
- (단점) ID 참조라 join 시 N+1 (한 쿼리당 추가 쿼리 N번 나가는 흔한 성능 함정) 주의 —
  읽기 쪽은 CQRS (Command 와 Query 모델을 분리) 로 별도 처리 (ADR-0004)
