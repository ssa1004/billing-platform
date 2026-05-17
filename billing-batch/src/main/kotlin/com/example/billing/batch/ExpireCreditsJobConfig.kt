package com.example.billing.batch

import com.example.billing.application.port.`in`.ExpireCreditsUseCase
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
 * 만료된 Credit 들을 EXPIRED 처리하는 batch.
 *
 * Tasklet 이 [ExpireCreditsUseCase.expireBatch] 를 결과 0 이 될 때까지 반복 호출.
 * 한 batch 단위 (limit) 가 한 트랜잭션이라 long-running 트랜잭션 회피.
 *
 * cron 은 `BillingJobScheduler.runExpireCredits()` 에서 매일 03:30 KST.
 * Settlement (03:00) 직후라 만료 처리 결과가 정산 보고서에 반영될 시간 있음.
 */
@Configuration
open class ExpireCreditsJobConfig(
    private val expireCredits: ExpireCreditsUseCase,
) {

    @Bean
    open fun expireCreditsJob(jobRepository: JobRepository, expireCreditsStep: Step): Job =
        JobBuilder("expireCreditsJob", jobRepository)
            .start(expireCreditsStep)
            .build()

    @Bean
    open fun expireCreditsStep(jobRepository: JobRepository, tx: PlatformTransactionManager): Step =
        StepBuilder("expireCreditsStep", jobRepository)
            .tasklet({ _, _ ->
                var totalProcessed = 0
                for (i in 0 until MAX_BATCHES_PER_RUN) {
                    val n = expireCredits.expireBatch(BATCH_LIMIT)
                    totalProcessed += n
                    if (n == 0) break
                }
                log.info("expireCreditsJob run finished, totalProcessed={}", totalProcessed)
                RepeatStatus.FINISHED
            }, tx)
            .build()

    companion object {
        private val log = LoggerFactory.getLogger(ExpireCreditsJobConfig::class.java)
        private const val BATCH_LIMIT = 200
        private const val MAX_BATCHES_PER_RUN = 100   // 안전 장치 — 한 run 에 최대 20,000건
    }
}
