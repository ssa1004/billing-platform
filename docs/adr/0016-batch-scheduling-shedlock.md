# ADR-0016: Batch Job 스케줄링 — `@Scheduled` + ShedLock

## 상태
적용

## 배경

billing-batch 모듈의 Spring Batch Job 들 (MonthlySettlementJob, ReconciliationJob) 을
**누가 / 언제 launch 할지** 가 명시되어 있지 않았음. Job 정의만 있고 trigger 없는 상태는
미완성.

운영 환경에서 batch job launch 의 일반적 옵션:

| 방법 | 장점 | 단점 |
|---|---|---|
| **K8s CronJob** | 별도 Pod, API 와 격리, K8s 가 retry/backoff 관리 | 매니페스트 추가, 별도 이미지 빌드 가능 |
| **Spring `@Scheduled`** | 코드만으로, 별도 Pod 불필요 | multi-instance 시 중복 실행 위험, API Pod 부하 |
| **외부 워크플로** (Argo Workflows / Airflow) | 복잡한 DAG 표현 가능 | 운영 복잡도 큼, 본 시스템엔 과함 |

## 결정

**`@Scheduled` + ShedLock** 채택.

### 이유

1. 현재 batch job 들이 단순 (단일 step, 짧은 처리 시간) 해서 K8s CronJob 분리 이득이 작음
2. ShedLock 으로 multi-instance 중복 실행 문제 해결 가능
3. application 코드와 같은 deploy artifact 라 배포 일관성 높음
4. 향후 batch 가 복잡해지면 K8s CronJob 으로 전환 가능 (Job 정의는 그대로 재사용)

### 구현

- `BillingJobScheduler` 가 cron 기반 trigger
- `@SchedulerLock(name="...", lockAtMostFor="PT1H")` 으로 분산 lock
- `JdbcTemplateLockProvider` + `usingDbTime()` (Pod 시계 drift 회피)
- `shedlock` 테이블은 V3 Flyway migration 으로 추가

```java
@Scheduled(cron = "0 0 3 1 * *", zone = "Asia/Seoul")
@SchedulerLock(name = "monthlySettlement",
               lockAtMostFor = "PT1H",
               lockAtLeastFor = "PT1M")
public void runMonthlySettlement() {
    YearMonth previousMonth = YearMonth.now(clock).minusMonths(1);
    JobParameters params = new JobParametersBuilder()
            .addString("period", previousMonth.toString())
            .addLong("triggeredAt", clock.millis())  // 매번 다른 instance 보장
            .toJobParameters();
    jobLauncher.run(monthlySettlementJob, params);
}
```

`lockAtLeastFor` 는 lock 이 너무 빨리 풀려서 다른 인스턴스가 같은 trigger 를 또 잡는 race
를 방지 (시계 drift 보호).

### `lockAtMostFor` 산정

가장 긴 batch 의 예상 실행 시간 + 안전 여유. 이 시간 안에 작업이 끝나지 않으면 lock 이
자동 해제되고 다음 인스턴스가 잡을 수 있음. 너무 짧으면 작업 중 다른 Pod 가 끼어들어
결과 오염 위험, 너무 길면 죽은 Pod 의 lock 이 오래 남음.

본 시스템 batch 별 산정:

| Job | 예상 시간 | lockAtMostFor |
|---|---|---|
| monthlySettlement | 30분 (대규모 customer 기준) | 1시간 |
| dailyReconciliation | 5~10분 (모든 Wallet 순회) | 30분 |

## 결과

- batch job 이 자동으로 정기 실행됨
- multi-instance (replica > 1) 환경에서 정확히 1 인스턴스만 실행
- 별도 Pod / 별도 매니페스트 불필요
- (한계) API Pod 와 같은 process 라 batch 가 무거우면 API 응답에 영향. 현재 batch 는 가벼워
  무관. 무거워지면 별도 Profile (`batch-only`) 로 분리한 Pod 운영 가능
- (한계) Spring Boot 시작 시 `spring.batch.job.enabled=false` 라야 시작 시 자동 실행 안 됨.
  application.yml 에서 명시적으로 끔
