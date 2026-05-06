# ADR-0015: 청구서의 가격 정책 snapshot

## 상태
적용

## 배경

PricingPlan 은 변경될 수 있다 (기업 고객 협상으로 요금제 갱신, 정책 인상 등). 그러나 과거에
이미 발행된 invoice 의 정산 금액은 절대 변하면 안 된다 — 회계 / 감사 요구사항.

대안:
- **변경 시 새 PricingPlan row 추가 (effective_from)**: 이미 적용 중. 하지만 invoice 가
  plan id 만 참조하면, plan 행이 수정되었을 때 (실수로 update 등) 과거 invoice 영향
- **Invoice 안에 사용된 가격 정보를 저장 (snapshot)**: invoice 가 PricingSnapshot 을 자체
  보유. plan 행이 어떻게 바뀌어도 무관

## 결정

`PricingSnapshot` 을 `Invoice` 에 포함시켜 JSON 으로 직렬화 저장.

```java
public final class Invoice {
    private final PricingSnapshot pricingSnapshot;
    // ...
}

// 청구서 생성 시
PricingPlan plan = pricingPlanRepository.findEffective(customer, period.toExclusive());
PricingSnapshot snapshot = plan.snapshot(clock.instant());
Invoice invoice = Invoice.draft(customer, period, lines, snapshot, clock);
```

`PricingSnapshot.calculate(resourceType, quantity)` 는 `PricingPlan.calculate` 와 동일한
계산을 하지만, snapshot 시점의 tier 정의를 사용.

## 결과

- plan 변경 후 과거 invoice 의 line amount 가 변하지 않음
- 영수증 / 정산 보고서가 invoice 만 보면 됨 (plan 조회 불필요)
- 회계 감사 시 "이 invoice 가 왜 이 금액인가" 답변 가능 — invoice 자체가 가격 정의를 가짐
- (단점) invoice 행이 커짐 (snapshot JSON 추가). 정산 1건 당 ~수 KB 추가. 무시 가능
- (단점) plan 갱신 후 첫 invoice 와 그 이전 invoice 의 line description 이 다를 수 있음
  (사용자가 다르게 보임). 이는 정상 — 가격이 실제로 다르기 때문
