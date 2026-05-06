package com.example.billing.batch.scheduler;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Spring Batch Job 의 정기 trigger.
 *
 * <p>schedule 정책:
 * <ul>
 *   <li><b>monthlySettlement</b> — 매월 1일 03:00 KST. 직전 월 (BillingPeriod) 정산 실행.</li>
 *   <li><b>dailyReconciliation</b> — 매일 04:00 KST. Wallet 잔액 ↔ Ledger 합계 검증.</li>
 * </ul>
 *
 * <p>각 schedule 은 {@link SchedulerLock} 으로 분산 lock 을 잡아 multi-instance 환경에서
 * 한 인스턴스만 실행한다. 다른 인스턴스는 즉시 skip.</p>
 *
 * <p>운영자 수동 트리거가 필요하면 별도 REST endpoint (예:
 * {@code POST /api/v1/settlement/run}) 또는 {@code Spring Batch Admin} 활용.</p>
 *
 * <p>K8s CronJob 패턴 (별도 Pod 에서 jar 실행) 과 비교하면 단순성이 장점이고, API Pod 에
 * 부하를 주는 것이 단점. 본 프로젝트의 batch 부하는 가벼워 본 방식을 채택. 큰 ETL 이 추가
 * 되면 별도 Pod 로 분리 권장.</p>
 */
@Component
@Profile({"prod", "scheduler"})
public class BillingJobScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingJobScheduler.class);

    private final JobLauncher jobLauncher;
    private final Job monthlySettlementJob;
    private final Job dailyReconciliationJob;
    private final Job expireCreditsJob;
    private final Job evaluateBudgetAlertsJob;
    private final Job deliverWebhooksJob;
    private final Clock clock;

    public BillingJobScheduler(JobLauncher jobLauncher,
                               @Qualifier("monthlySettlementJob") Job monthlySettlementJob,
                               @Qualifier("dailyReconciliationJob") Job dailyReconciliationJob,
                               @Qualifier("expireCreditsJob") Job expireCreditsJob,
                               @Qualifier("evaluateBudgetAlertsJob") Job evaluateBudgetAlertsJob,
                               @Qualifier("deliverWebhooksJob") Job deliverWebhooksJob,
                               Clock clock) {
        this.jobLauncher = jobLauncher;
        this.monthlySettlementJob = monthlySettlementJob;
        this.dailyReconciliationJob = dailyReconciliationJob;
        this.expireCreditsJob = expireCreditsJob;
        this.evaluateBudgetAlertsJob = evaluateBudgetAlertsJob;
        this.deliverWebhooksJob = deliverWebhooksJob;
        this.clock = clock;
    }

    /**
     * 매월 1일 03:00 KST. 직전 월의 정산 실행.
     * 실행 시간은 SettlementRun 큐 길이에 따라 다르나, lockAtMostFor 1시간 안에서 끝나야 함.
     */
    @Scheduled(cron = "0 0 3 1 * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "monthlySettlement", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void runMonthlySettlement() {
        YearMonth previousMonth = YearMonth.now(clock.withZone(ZoneOffset.UTC)).minusMonths(1);
        String periodKey = previousMonth.toString();
        try {
            JobParameters params = new JobParametersBuilder()
                    .addString("period", periodKey)
                    .addLong("triggeredAt", clock.millis())  // 매번 다른 instance 보장
                    .toJobParameters();
            log.info("triggering monthlySettlementJob for period={}", periodKey);
            jobLauncher.run(monthlySettlementJob, params);
        } catch (Exception e) {
            log.error("monthlySettlementJob failed for period={}", periodKey, e);
        }
    }

    /**
     * 매일 04:00 KST. Wallet 잔액과 Ledger 합계 정합성 검증.
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "dailyReconciliation", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void runDailyReconciliation() {
        try {
            JobParameters params = new JobParametersBuilder()
                    .addLong("triggeredAt", clock.millis())
                    .toJobParameters();
            log.info("triggering dailyReconciliationJob");
            jobLauncher.run(dailyReconciliationJob, params);
        } catch (Exception e) {
            log.error("dailyReconciliationJob failed", e);
        }
    }

    /**
     * 매일 03:30 KST. 만료 시점 도달한 ACTIVE Credit 들을 EXPIRED 처리.
     * Settlement (03:00) 직후라 만료 결과가 그날 정산 보고서에 반영 가능.
     * 운영자 수동 트리거가 필요하면 별도 endpoint 추가.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "expireCredits", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void runExpireCredits() {
        try {
            JobParameters params = new JobParametersBuilder()
                    .addLong("triggeredAt", clock.millis())
                    .toJobParameters();
            log.info("triggering expireCreditsJob");
            jobLauncher.run(expireCreditsJob, params);
        } catch (Exception e) {
            log.error("expireCreditsJob failed", e);
        }
    }

    /**
     * 매 시간 정각. ACTIVE BudgetAlertRule 평가 → 임계 초과 시 알림.
     * 한 시간 빈도면 cooldown (24h) 안에서 같은 사용자에게 두 번 알림 가지 않음.
     */
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "evaluateBudgetAlerts", lockAtMostFor = "PT15M", lockAtLeastFor = "PT30S")
    public void runEvaluateBudgetAlerts() {
        try {
            JobParameters params = new JobParametersBuilder()
                    .addLong("triggeredAt", clock.millis())
                    .toJobParameters();
            log.info("triggering evaluateBudgetAlertsJob");
            jobLauncher.run(evaluateBudgetAlertsJob, params);
        } catch (Exception e) {
            log.error("evaluateBudgetAlertsJob failed", e);
        }
    }

    /**
     * 매 분. PENDING webhook delivery 들을 잡아 customer 서버로 HTTP POST.
     * 1분 안에 처리 못 한 건은 다음 분으로 자연 이월. lockAtMostFor 짧게 (PT5M) — 한 인스턴스가
     * 멈춰도 빠르게 다른 인스턴스가 인계.
     */
    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "deliverWebhooks", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void runDeliverWebhooks() {
        try {
            JobParameters params = new JobParametersBuilder()
                    .addLong("triggeredAt", clock.millis())
                    .toJobParameters();
            jobLauncher.run(deliverWebhooksJob, params);
        } catch (Exception e) {
            log.error("deliverWebhooksJob failed", e);
        }
    }
}
