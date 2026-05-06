package com.example.billing.batch;

import com.example.billing.application.port.in.EvaluateBudgetAlertsUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * BudgetAlertRule 평가 batch.
 *
 * <p>Tasklet 이 {@link EvaluateBudgetAlertsUseCase#evaluateAll} 한번 호출.
 * 내부에서 customer 단위 트랜잭션으로 처리해 한 customer 실패가 전체를 막지 않음.</p>
 *
 * <p>cron 은 {@code BillingJobScheduler.runEvaluateBudgetAlerts()} 에서 매 시간 정각.</p>
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class EvaluateBudgetAlertsJobConfig {

    private final EvaluateBudgetAlertsUseCase evaluateBudgetAlerts;

    @Bean
    public Job evaluateBudgetAlertsJob(JobRepository jobRepository, Step evaluateBudgetAlertsStep) {
        return new JobBuilder("evaluateBudgetAlertsJob", jobRepository)
                .start(evaluateBudgetAlertsStep)
                .build();
    }

    @Bean
    public Step evaluateBudgetAlertsStep(JobRepository jobRepository, PlatformTransactionManager tx) {
        return new StepBuilder("evaluateBudgetAlertsStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    int evaluated = evaluateBudgetAlerts.evaluateAll();
                    log.info("evaluateBudgetAlertsJob run finished, evaluated={}", evaluated);
                    return RepeatStatus.FINISHED;
                }, tx)
                .build();
    }
}
