package com.example.billing.batch;

import com.example.billing.application.port.in.DeliverPendingWebhooksUseCase;
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
 * Webhook 발송 워커 batch.
 *
 * <p>Tasklet 이 {@link DeliverPendingWebhooksUseCase#deliverBatch} 를 결과 0 이 될 때까지 반복.
 * 한 배치 = 한 트랜잭션 (claim + send + save) → SKIP LOCKED 락 유지.</p>
 *
 * <p>cron 은 {@code BillingJobScheduler.runDeliverWebhooks} 에서 매 분. 1분 안에 한 batch 가
 * 끝나는 사이즈로 {@link #BATCH_LIMIT} 조정 — 너무 크면 트랜잭션이 길어지고 다른 워커/운영자
 * 작업이 막힘.</p>
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class DeliverWebhooksJobConfig {

    /** 한 batch 에 잡을 최대 delivery 수. customer 서버 응답 평균 ~1초 가정 시 5건 * 1초 = 5초/batch. */
    private static final int BATCH_LIMIT = 5;
    /** 한 run 의 누적 batch 상한 — 안전 장치. */
    private static final int MAX_BATCHES_PER_RUN = 50;

    private final DeliverPendingWebhooksUseCase deliverWebhooks;

    @Bean
    public Job deliverWebhooksJob(JobRepository jobRepository, Step deliverWebhooksStep) {
        return new JobBuilder("deliverWebhooksJob", jobRepository)
                .start(deliverWebhooksStep)
                .build();
    }

    @Bean
    public Step deliverWebhooksStep(JobRepository jobRepository, PlatformTransactionManager tx) {
        return new StepBuilder("deliverWebhooksStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    int total = 0;
                    for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
                        int n = deliverWebhooks.deliverBatch(BATCH_LIMIT);
                        total += n;
                        if (n == 0) break;
                    }
                    log.info("deliverWebhooksJob run finished total={}", total);
                    return RepeatStatus.FINISHED;
                }, tx)
                .build();
    }
}
