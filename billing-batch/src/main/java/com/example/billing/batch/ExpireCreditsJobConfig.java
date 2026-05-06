package com.example.billing.batch;

import com.example.billing.application.port.in.ExpireCreditsUseCase;
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
 * 만료된 Credit 들을 EXPIRED 처리하는 batch.
 *
 * <p>Tasklet 이 {@link ExpireCreditsUseCase#expireBatch} 를 결과 0 이 될 때까지 반복 호출.
 * 한 batch 단위 (limit) 가 한 트랜잭션이라 long-running 트랜잭션 회피.</p>
 *
 * <p>cron 은 {@code BillingJobScheduler.runExpireCredits()} 에서 매일 03:30 KST.
 * Settlement (03:00) 직후라 만료 처리 결과가 정산 보고서에 반영될 시간 있음.</p>
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class ExpireCreditsJobConfig {

    private static final int BATCH_LIMIT = 200;
    private static final int MAX_BATCHES_PER_RUN = 100;   // 안전 장치 — 한 run 에 최대 20,000건

    private final ExpireCreditsUseCase expireCredits;

    @Bean
    public Job expireCreditsJob(JobRepository jobRepository, Step expireCreditsStep) {
        return new JobBuilder("expireCreditsJob", jobRepository)
                .start(expireCreditsStep)
                .build();
    }

    @Bean
    public Step expireCreditsStep(JobRepository jobRepository, PlatformTransactionManager tx) {
        return new StepBuilder("expireCreditsStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    int totalProcessed = 0;
                    for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
                        int n = expireCredits.expireBatch(BATCH_LIMIT);
                        totalProcessed += n;
                        if (n == 0) break;
                    }
                    log.info("expireCreditsJob run finished, totalProcessed={}", totalProcessed);
                    return RepeatStatus.FINISHED;
                }, tx)
                .build();
    }
}
