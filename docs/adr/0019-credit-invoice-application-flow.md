# ADR-0019: Credit ↔ Invoice 적용 흐름 + 만료 batch

## 상태
적용

## 배경

ADR-0018 에서 Credit 애그리거트를 만들었습니다. 다음 단계로 실제로 Invoice 결제 금액을
줄이는 적용 경로와, 발급 시점에 정한 `validUntil` (유효 종료 시점) 을 실제로 만료시키는
경로가 필요합니다.

## 결정

### Invoice 모델 확장

```
Invoice
  total            : Money     (변경 불가)
  appliedCredit    : Money     (0 부터 누적, 0 ≤ x ≤ total)
  amountDue()      : Money     = total - appliedCredit
```

- `Invoice.applyCredit(amount)` — `ISSUED` 상태일 때만 허용. amountDue 초과는 거부. 통화가
  맞지 않으면 (mismatch) 거부.
- `amountDue == 0` 이 되어도 자동 PAID 전환은 안 함. 결제 service 가 ledger 와 함께 별도로
  처리합니다 (PAID 는 회계상 사건이므로 결제 도메인의 책임이고, Credit 적용은 잔액만 줄임).

### ApplyCreditService 흐름 (한 트랜잭션)

```
1. Invoice 로드 (없으면 InvoiceNotFoundException, DRAFT 면 Invoice 가 거부)
2. realCap = min(cmd.applyAtMost, invoice.amountDue())
3. CreditRepository.findUsable(customerId, now)  — 만료 임박 → FIFO 정렬
4. for credit in usable:
     take = min(credit.balance, realCap - applied)
     credit.consume(take, ref="invoice:<id>", clock)
     credits.save(credit)
     events.publish(CreditConsumed)
     applied += take
     if applied >= realCap: break
5. invoice.applyCredit(applied) → invoices.save(invoice)
```

원자성: 한 트랜잭션 안에서 Credit 차감 과 Invoice.appliedCredit 증가 가 같이 묶여서
commit / rollback 됩니다. 한쪽만 반영된 상태로 남는 정합 사고 회피.

낙관적 락 충돌 (Credit / Invoice 의 `@Version` 이 안 맞아 OptimisticLockException) 이 나면
전체 rollback. ApplyCreditService 는 짧은 budget (3회 × 50ms) 안에서 자동 재시도하고, budget
을 넘기면 호출자에게 예외를 그대로 throw. 충돌은 만료 batch (같은 Credit 의 status 를
EXPIRED 로 바꿈) / 동시에 같은 invoice 에 결제 시도 등에서 발생할 수 있습니다.

### 통화 mismatch 처리

Credit 의 통화가 Invoice 통화와 다르면 그 Credit 은 skip 합니다.
환율 변환은 의도적으로 하지 않습니다 — 환율 적용 시점 / rate provider (환율 제공자) / 회계
처리가 별도 도메인이라 이 service 의 책임 범위 밖입니다. KRW 청구서에 USD credit 을 같이
보유한 경우, USD 분은 다른 USD 청구서에서만 사용됩니다.

### 만료 batch

`ExpireCreditsJobConfig` Spring Batch + `BillingJobScheduler.runExpireCredits()` (매일 03:30
KST, ShedLock 으로 인스턴스 중 하나만 실행).

- Tasklet 이 `ExpireCreditsUseCase.expireBatch(LIMIT)` 를 결과 0 이 될 때까지 반복 호출
- 한 batch (= 한 트랜잭션) 단위는 200건 — 너무 긴 트랜잭션 / 락 경합을 회피
- 한 run 의 상한은 200 × 100 = 20,000건 (안전 장치)
- `Credit.expire(clock)` 가 만료로 사라지는 잔액 (forfeit) 을 이벤트에 담아 publish — 회계 /
  알림 / 분석 시스템의 입력

## 대안 검토

- **Credit 적용 시 자동 PAID 전환** — Credit 으로 100% 커버되면 즉시 PAID 로 바꾸는 안. 거부.
  결제 도메인의 invariant (ledger 정합 / Payment record 생성) 가 깨짐. ApplyCredit 는 잔액
  조정만, 실제 결제는 Payment service 의 책임.
- **Credit 적용을 별도 ledger entry 로 표현** — Ledger 에 `CREDIT_APPLIED` 타입 entry 추가.
  채택 가능하지만 invoice 상태와 ledger 두 곳에 진실이 흩어져 정합성 맞추는 (reconciliation)
  비용 증가. 현재는 invoice 의 `applied_credit` 컬럼이 단일 진실 (single source of truth).
- **만료를 도메인 메서드 호출 시 lazy 처리** — `Credit.consume()` 에서 만료 체크해 자동
  EXPIRED 전이. 채택 안 함 — 만료는 발생 시점 에 대한 audit log (감사 기록) 가 필요 (회계
  / 알림 용). 명시적 batch 가 정확.

## 결과

- Invoice 의 `amountDue()` 가 결제 service / 미수금 (aged receivables) 의 결제 대상 금액
  컬럼
- Credit 발급 → 적용 → 만료 라이프사이클 전체가 닫힌 회로로 마무리됨
- 운영 화면에서 "이 invoice 에 적용된 credit 합계" 를 한 컬럼으로 즉시 조회 가능
- (단점) Credit 을 여러 개 보유한 customer 의 적용은 update 가 N 번 발생 — 동시성 경합 가능.
  문제 되면 customer 단위 advisory lock 도입 검토

## 참고

- 실무 SaaS billing 에서 invoice 의 `amount_due` 는 `total - credit - 선납금 (prepayment) -
  분쟁 보류 (dispute_hold)` 같은 식으로 더 복잡. 본 ADR 은 credit 만 다룸; prepayment /
  dispute 는 후속 ADR.
