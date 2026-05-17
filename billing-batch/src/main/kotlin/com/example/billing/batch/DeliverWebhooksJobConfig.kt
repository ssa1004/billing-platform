package com.example.billing.batch

import com.example.billing.application.port.`in`.DeliverPendingWebhooksUseCase
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * Webhook 발송 워커 batch.
 *
 * Tasklet 이 [DeliverPendingWebhooksUseCase.deliverBatch] 를 결과 0 이 될 때까지 반복.
 * 한 배치 = 한 트랜잭션 (claim + send + save) → SKIP LOCKED 락 유지.
 *
 * cron 은 `BillingJobScheduler.runDeliverWebhooks` 에서 매 분. 1분 안에 한 batch 가
 * 끝나는 사이즈로 [BATCH_LIMIT] 조정 — 너무 크면 트랜잭션이 길어지고 다른 워커/운영자
 * 작업이 막힘.
 */
@Configuration
open class DeliverWebhooksJobConfig(
    private val deliverWebhooks: DeliverPendingWebhooksUseCase,
) {

    @Bean
    open fun deliverWebhooksJob(jobRepository: JobRepository, deliverWebhooksStep: Step): Job =
        JobBuilder("deliverWebhooksJob", jobRepository)
            .start(deliverWebhooksStep)
            .build()

    @Bean
    open fun deliverWebhooksStep(jobRepository: JobRepository, tx: PlatformTransactionManager): Step =
        StepBuilder("deliverWebhooksStep", jobRepository)
            .tasklet({ _, _ ->
                var total = 0
                for (i in 0 until MAX_BATCHES_PER_RUN) {
                    val n = deliverWebhooks.deliverBatch(BATCH_LIMIT)
                    total += n
                    if (n == 0) break
                }
                log.info("deliverWebhooksJob run finished total={}", total)
                RepeatStatus.FINISHED
            }, tx)
            .build()

    companion object {
        private val log = LoggerFactory.getLogger(DeliverWebhooksJobConfig::class.java)

        /** 한 batch 에 잡을 최대 delivery 수. customer 서버 응답 평균 ~1초 가정 시 5건 * 1초 = 5초/batch. */
        private const val BATCH_LIMIT = 5

        /** 한 run 의 누적 batch 상한 — 안전 장치. */
        private const val MAX_BATCHES_PER_RUN = 50
    }
}
