# ADR-0020: 월말 사용량/비용 예상 (UsageForecast)

## 상태
적용

## 배경

사용량 기반 SaaS 빌링에서 *월 중간 시점에* 고객/운영자가 알고 싶은 것:

- 이번 달 청구서가 얼마쯤 나올까 (예상)
- 현재 페이스대로면 무료 tier 를 언제 초과할까
- 특정 resource (예: 데이터 전송) 가 평균보다 빠르게 증가하지 않나

월말이 되어야 알 수 있는 청구서 (`Invoice`) 와 별개로, 현재까지의 사용량을 기반으로
*예측치* 를 제공할 read-only API 가 필요하다.

## 결정

### 도메인 모델

`UsageForecast` 는 **value object** (record). aggregate 가 아니다 — 저장하지 않고
요청 시마다 계산된다.

```java
record UsageForecast(
    CustomerId customerId,
    BillingPeriod period,
    Instant asOf,
    double periodProgressRatio,            // [0, 1]
    List<ResourceForecast> resources,
    Money projectedTotalCost
)

record ResourceForecast(
    ResourceType resourceType,
    long mtdQuantity,
    long projectedQuantity,
    Money mtdCost,
    Money projectedCost
)
```

### 외삽 알고리즘 (현재)

선형 외삽:

```
progress = (asOf - periodStart) / (periodEnd - periodStart)   ∈ (0, 1]
projectedQuantity = round(mtdQuantity / progress)
projectedCost     = pricingPlan.calculate(projectedQuantity)
```

`progress` 가 너무 작으면 (월 1일 새벽) 0 으로 나누는 거나 마찬가지로 분산이 폭발 →
임계값 (0.001) 미만에서는 `projected = mtd` (외삽 안 함).

### Service 분리

`UsageForecastService implements UsageForecastUseCase`.
`AggregatedUsageRepository` + `PricingPlanRepository` 두 read 포트만 사용.
`@Transactional(readOnly = true)`. write side 와 분리.

### REST

```
GET /api/v1/usage/forecast?customerId=X
```

응답에 `periodProgressRatio` 를 포함 — 화면에서 "월 시작 직후라 예측 신뢰도 낮음"
같은 disclaimer 를 표시할지 결정 가능.

## 대안 검토

- **저장형 (aggregate) 으로 모델링** — 매일 새벽 batch 가 forecast 를 계산해 저장.
  채택 안 함. 요청 빈도가 낮고 (대시보드 / 알림), 실시간성 (방금 ingest 된 사용량 반영)
  이 가치 있음. 캐시는 필요해지면 read-side 에 추가.
- **시계열 모델 (ARIMA / Prophet)** — 정확도 향상. 채택 안 함.
  - 도메인 코드에 ML 의존성 추가는 운영 비용 증가
  - 첫 버전은 "직관적이고 검증 가능한" 선형 외삽이 나음
  - 정확도 개선은 service 만 교체 (domain record 는 그대로)
- **고정 분포 가정 (예: 평일/주말 비율)** — 단순 선형보다 정확하지만 도메인마다
  분포가 다름 (B2B 는 주말 거의 0, B2C 는 주말 spike). 모델링 비용 대비 가치 불분명.
  필요해지면 `ForecastStrategy` 인터페이스로 추상화하고 customer / industry 별로 선택.

## 결과

- 운영 화면 / 사용자 대시보드 / 임계 알림 (`projectedTotalCost > X` 시 발송) 의 백엔드
- 도메인 모델 (`record`) 은 알고리즘 변경에 영향 없음 — service 만 교체하면 됨
- 진행률 (`periodProgressRatio`) 노출로 화면 측이 신뢰도를 표현 가능
- (단점) 월 초 신뢰도 낮음 — UX 보정 필요
- (단점) plan 변경이 월 중에 발생하면 부정확 — 현재 시점 plan 만 사용

## 후속 후보 (이 ADR 의 경계 밖)

- `BudgetAlertRule` 도메인 — `projectedTotalCost > threshold` 면 알림 발송
- 분 단위 / 시간 단위 forecast (현재는 monthly 만)
- 시계열 모델 도입 (Prophet / ARIMA) — `ForecastStrategy` 추상화 후
