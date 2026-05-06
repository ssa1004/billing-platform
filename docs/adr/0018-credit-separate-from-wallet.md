# ADR-0018: Credit (선불/프로모 잔액) 을 Wallet 과 분리

## 상태
적용

## 배경

청구서 발행 직전 차감되는 *비-환전성* 잔액 풀이 필요. 마케팅 프로모 / 신규 가입 free credit /
SLA 위반 보상 / 환불을 현금 대신 크레딧으로 적립 등 케이스가 많음. 이 잔액은:

- 거래 입금 / 출금이 아니라 *발급* 됨 (운영자 / 마케팅 시스템 / CS)
- 만료 가능 (PROMO 는 보통 30~90일)
- 환불 불가 (현금이 아님)
- 회계상 부채 인식 시점이 거래 잔액과 다름 (수익 인식 회계 처리 별도)
- 청구서에 자동 적용되어 결제 대상 금액을 줄임

## 결정

기존 {@code Wallet} 애그리거트와 합치지 않고 **별도 {@code Credit} 애그리거트** 를 둔다.

```
Customer ─┬─ Wallet      (현금성 잔액. 입금 / 출금 / 블록 / 환불 가능)
          │
          └─ Credit*     (발급된 잔액. 차감 / 만료 / 회수. 환불 불가)
```

### Credit 모델

| 필드 | 의미 |
|---|---|
| `id` | UUID |
| `customerId` | 발급 대상 |
| `type` | PROMO / PREPAID / COMPENSATION / REFUND_TO_CREDIT |
| `currency`, `grantedAmount`, `balance` | 잔액 추적 |
| `validFrom`, `validUntil` | 유효기간 (`null` = 만료 없음) |
| `status` | ACTIVE → EXHAUSTED / EXPIRED / REVOKED |
| `reason` | free text (CS 메모 등) |
| `version` | optimistic lock |

### 차감 우선순위

`CreditRepository.findUsable` 정렬:

1. 만료 임박한 것 먼저 (만료 손실 최소화)
2. 만료 같으면 발급 시점 빠른 것 (FIFO)

### 적용 흐름

1. Invoice 가 `issued` 상태로 발행
2. `ApplyCreditUseCase.apply(invoice.totalAmount)` 호출 — 사용 가능 Credit 들 합산 차감
3. Invoice 의 결제 대상 금액 = `total - appliedCredit` (별도 service / 호출자 책임)

이 ADR 의 경계는 *Credit 자체* — Invoice 와의 연동 service 는 별도 (ADR-0019 후보).

### 만료 batch

`CreditRepository.findExpiredCandidates` → `Credit.expire(clock)` → `CreditExpired` 이벤트 발행.
`@Scheduled` 로 일 1회. 잔여 잔액은 `forfeitedBalance` 로 이벤트에 기록 → 회계 / 알림 / 분석에 사용.

## 대안 검토

- **Wallet 에 `walletType` 필드 추가** — invariant 가 다르고 (Wallet 은 입금/출금/블록 모두,
  Credit 은 차감/만료/회수만), 회계 처리 분리도 깨져 거부.
- **Ledger entry 만으로 모델링** — Ledger 는 *기록* 용. 만료 / 우선순위 / 회수 같은 상태
  라이프사이클을 다루기엔 표현력 부족.
- **Pricing 단계에서 할인으로 처리** — 사용량 기반 할인 (예: free tier) 과 *외부에서 발급된*
  크레딧은 출처 / 회계 처리 / 만료가 다름. Pricing 은 *plan* 의 영역이라 분리.

## 결과

- 도메인 표현이 명확 — `wallet.deposit` 과 `credit.grant` 가 의도적으로 다른 메서드
- 만료 / 회수 같은 Credit 고유 라이프사이클을 Wallet 코드에 침범 없이 추가 가능
- 회계 처리도 Ledger 에 entry type 분리로 자연스럽게 표현
- (단점) customer 별 잔액 조회 시 두 테이블 join 필요 — 운영 화면용 read model 따로 만들면 해결

## 참고

- 실무 multi-tenant SaaS 빌링 시스템에서 Credit / Coupon / Discount 분리 패턴은 흔함
- 발급 → 만료 → 회수 라이프사이클은 어느 시스템에서도 동일
