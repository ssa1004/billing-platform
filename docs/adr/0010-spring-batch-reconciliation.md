# ADR-0010: Spring Batch chunk + skip + retry 정산

## 상태
적용

## 배경
일일 정산 (reconciliation, 잔액과 거래 내역이 일치하는지 맞추는 작업) — 모든 wallet 의
잔액과 ledger_entries (거래 내역) 합계가 일치하는지 검증해서 코드 버그나 운영 사고를 잡아야
합니다. 수만 ~ 수십만 wallet 을 처리할 수 있어야 하고, 일부 레코드가 실패해도 전체 batch 가
멈추면 안 됩니다.

## 결정
**Spring Batch** (대용량 일괄 처리용 프레임워크). chunk 단위 step:
- `JpaPagingItemReader` (size=100) — 한 번에 100건씩 페이징해서 메모리 효율 확보
- processor / writer
- `faultTolerant().skipLimit(10).skip(IllegalStateException.class)` — 정합성 깨진 wallet
  은 skip 하고 별도 알림. 전체 배치는 계속 진행 (skip 한도 10건)

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
- 대용량 처리 (수십만 row) 에서도 메모리 안전
- 일부 실패가 전체를 막지 않음
- Spring Batch 의 metadata 테이블이 Job execution 이력을 자동 보존 (실패 지점에서 재시작
  가능)
- (단점) Spring Batch metadata 테이블 (BATCH_* 시리즈) 을 추가해야 함 (자동 init 가능)
- (단점) 단순 case 엔 과도한 추상화 — 본 정산은 매일 돌아가야 해서 적합
