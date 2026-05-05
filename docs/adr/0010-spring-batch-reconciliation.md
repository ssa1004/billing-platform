# ADR-0010: Spring Batch chunk + skip + retry 정산

## 상태
적용

## 배경
일일 정산 — 모든 wallet 의 잔액과 ledger_entries 합계가 일치하는지 검증 (코드 버그 / 운영 사고 catch). 수만~수십만 wallet 을 처리할 수 있어야 함. 일부 레코드 실패해도 전체 batch 가 안 막혀야 함.

## 결정
**Spring Batch.** chunk-oriented step:
- `JpaPagingItemReader` (size=100) — 메모리 효율
- processor / writer
- `faultTolerant().skipLimit(10).skip(IllegalStateException.class)` — 정합성 깨진 wallet 은 skip + 별도 알림, 전체는 계속

```java
@Bean
public Step reconciliationStep() {
    return new StepBuilder("reconciliationStep", jobRepository)
        .<WalletJpaEntity, WalletJpaEntity>chunk(100, tx)
        .reader(walletReader())
        .writer(reconciliationWriter())
        .faultTolerant().skipLimit(10).skip(IllegalStateException.class)
        .build();
}
```

스케줄러는 Quartz / k8s CronJob — 현재 코드는 명령형 실행 가능 상태만.

## 결과
- 대용량 처리 (수십만 row) 메모리 안전
- 일부 실패가 전체 막지 않음
- Spring Batch metadata 가 Job execution 이력 자동 보존 (재시작 가능)
- (단점) Spring Batch 의 metadata 테이블 (BATCH_*) 추가 필요 (자동 init 가능)
- (단점) 단순 case 엔 over-engineering — 본 정산은 매일 돌아가야 해서 적합
