# ADR-0015: 청구서의 가격 정책 snapshot

## 상태
적용

## 배경

PricingPlan 은 변경될 수 있습니다 (기업 고객 협상으로 요금제 갱신, 정책 인상 등). 그러나
과거에 이미 발행된 invoice 의 정산 금액은 절대 변하면 안 됩니다 — 회계 / 감사 요구사항.

대안:
- **변경 시 새 PricingPlan row 를 추가 (effective_from 컬럼으로 시작 시점 표기)**: 이미
  적용 중. 하지만 invoice 가 plan id 만 참조하면, plan 행이 수정되었을 때 (실수로 update
  등) 과거 invoice 가 영향을 받음
- **Invoice 안에 사용된 가격 정보를 박제해서 저장 (snapshot)**: invoice 가 PricingSnapshot
  을 자체 보유. plan 행이 어떻게 바뀌어도 무관

## 결정

`PricingSnapshot` (그 시점 요금표를 통째로 박제한 값 객체) 을 `Invoice` 에 포함시켜 JSON
으로 직렬화 저장.

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
계산을 수행하지만, snapshot 시점의 구간(tier) 정의를 사용합니다.

## 결과

- plan 변경 후에도 과거 invoice 의 line 금액이 변하지 않음
- 영수증 / 정산 보고서가 invoice 만 보면 됨 (plan 을 따로 조회할 필요 없음)
- 회계 감사 시 "이 invoice 가 왜 이 금액인가" 에 답할 수 있음 — invoice 자체가 가격 정의를
  품고 있음
- (단점) invoice row 가 커짐 (snapshot JSON 추가). 정산 1건 당 수 KB 정도. 무시 가능한 수준
- (단점) plan 갱신 후 첫 invoice 와 그 이전 invoice 의 line description 이 다를 수 있음
  (사용자 화면에서 다르게 보임). 이는 정상 — 가격이 실제로 달라졌기 때문
