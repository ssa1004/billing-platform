# ADR-0021: BudgetAlertRule — 월말 예상 청구액 임계 알림

## 상태
적용

## 배경

ADR-0020 의 `UsageForecast` 위에 임계 초과 알림이 자연스러운 후속입니다. 사용자/운영자가
"이번 달 예상이 X 원 넘으면 알려줘" 를 정의 → 시스템이 주기적으로 평가 → 초과 시 customer
알림 채널로 push. 사고성 비용 폭증 (잘못된 코드로 사용량 폭주, 외부 abuse 등) 의 조기 대응
통로입니다.

## 결정

### 도메인 모델

`BudgetAlertRule` 애그리거트:

| 필드 | 의미 |
|---|---|
| `id` | UUID |
| `customerId` | 알림 받을 customer |
| `threshold` | 임계 금액 (Money — 통화 포함) |
| `cooldown` | 같은 rule 재트리거 최소 간격 (default 24h) |
| `status` | ACTIVE / PAUSED |
| `lastEvaluatedAt` | 마지막 평가 시각 (UI 신선도 표시) |
| `lastTriggeredAt` | 마지막 트리거 시각 (cooldown 계산) |
| `version` | 낙관적 락 (optimistic lock) 용 버전 |

### Cooldown (재트리거 사이의 휴지 간격) 의 이유

스케줄러가 매 시간 평가하므로 cooldown 이 없으면 임계 초과 상태가 계속 유지되는 동안 매
시간 알림이 가서 스팸이 됩니다. 24h cooldown 으로 같은 사용자에게 같은 rule 의 알림은 하루
1회로 제한합니다.

다단계 알림이 필요하면 (yellow $100 / red $500 / critical $1000) rule 을 여러 개 만들면
됩니다 — 각 rule 의 cooldown 은 독립적입니다.

### Evaluate 흐름

```
EvaluateBudgetAlertsService.evaluateAll()
  → rules.findCustomersWithActiveRules()       // ACTIVE rule 1개 이상인 customer
  → for each customer:
      forecast = UsageForecastUseCase.forecastCurrentPeriod(customer)
      for rule in rules.findActiveByCustomer(customer):
        if rule.threshold.currency == forecast.projectedTotalCost.currency:
          rule.evaluate(projected, clock)
            → if triggered: events.publish(Triggered) + notifier.notify(BUDGET_ALERT, context)
          rules.save(rule)   // lastEvaluatedAt 갱신 보장
```

- customer 단위 트랜잭션 — 한 customer 의 forecast 가 `PricingPlanNotFoundException`
  으로 실패해도 다른 customer 평가는 계속 진행
- 통화가 맞지 않으면 (mismatch) skip — 다중 통화 분기는 후속 (현재는 단일 통화 가정)

### Scheduling

Spring Batch tasklet + `BillingJobScheduler.runEvaluateBudgetAlerts()`:
- cron: 매 시간 정각 (UTC)
- ShedLock (인스턴스 중 하나만 실행되게 막아주는 분산 락) `lockAtMostFor=PT15M,
  lockAtLeastFor=PT30S`
- 매 시간 빈도 + 24h cooldown → 같은 사용자 같은 rule 은 하루 1회 알림 보장

### Notification context

`CustomerNotifier.NotificationType.BUDGET_ALERT` 추가. context map:
```
{
  ruleId, threshold, projectedCost, currency, overshootRatio,
  period ("2026-05"), periodProgressRatio
}
```

`overshootRatio` (1.5 = 임계의 1.5배, 즉 50% 초과) 와 `periodProgressRatio` 를 같이 넘겨
알림 본문에서 "5월 50% 진행, 예상 150만 — 임계 100만 의 1.5배" 같은 컨텍스트를 전달
가능합니다.

## 대안 검토

- **Aggregate 없이 인라인으로 rule 처리** — `customers.budget_threshold` 같은 단순 컬럼.
  채택 안 함. 다단계 / cooldown / 일시 정지 / 마지막 트리거 시각 (`lastTriggered`) 등 audit
  정보가 다 필요해 결국 aggregate 가 됨. 단순 컬럼으로 두면 첫 사용자 요청에서 깨질 모델.
- **실시간 트리거 (Outbox 이벤트 기반)** — 사용량이 들어올 때마다 forecast 재계산 + alert
  평가. 채택 안 함. forecast 비용 (aggregated_usage scan + pricing.calculate) 대비 가치가
  낮음. 매 시간이면 실시간에 충분히 가까움. 정말 필요하면 `OutboxRelay` 컨슈머로 분기.
- **Push 가 아닌 Pull** — 사용자가 대시보드에서 polling. 사고성 비용 알림은 push 가 맞음.

## 결과

- 운영 / 사용자가 직접 임계를 정의하고 시스템이 능동적으로 알림 — 사고성 비용 폭증에 조기
  대응
- Forecast (ADR-0020) 와 Notifier (`CustomerNotifier`) 위에 얇게 얹어 구현 — 새 의존성 0
- 다단계 알림은 rule 여러 개로 자연스럽게 표현
- (단점) 1시간 지연 — 실시간 대응이 필요해지면 후속 ADR 에서 outbox 기반으로 보강
- (단점) 다중 통화는 현재 skip — 대부분 SaaS 는 customer 가 단일 통화라 OK. 다중 통화
  customer 가 들어오면 분기 추가

## 후속 후보

- `pause` / `resume` REST endpoint (도메인은 이미 지원, controller 만 추가)
- `BudgetAlertHistory` 별도 테이블 — `Triggered` 이벤트 영속화 + 화면 timeline
- 실시간 (outbox-driven) 알림 옵션
- 멀티 통화 분기 / 환율 변환 (FX rate provider 도입 후)
