package com.example.billing.batch

import com.example.billing.application.port.`in`.EvaluateBudgetAlertsUseCase
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
 * BudgetAlertRule 평가 batch.
 *
 * Tasklet 이 [EvaluateBudgetAlertsUseCase.evaluateAll] 한번 호출.
 * 내부에서 customer 단위 트랜잭션으로 처리해 한 customer 실패가 전체를 막지 않음.
 *
 * cron 은 `BillingJobScheduler.runEvaluateBudgetAlerts()` 에서 매 시간 정각.
 */
@Configuration
open class EvaluateBudgetAlertsJobConfig(
    private val evaluateBudgetAlerts: EvaluateBudgetAlertsUseCase,
) {

    @Bean
    open fun evaluateBudgetAlertsJob(
        jobRepository: JobRepository,
        evaluateBudgetAlertsStep: Step,
    ): Job =
        JobBuilder("evaluateBudgetAlertsJob", jobRepository)
            .start(evaluateBudgetAlertsStep)
            .build()

    @Bean
    open fun evaluateBudgetAlertsStep(
        jobRepository: JobRepository,
        tx: PlatformTransactionManager,
    ): Step =
        StepBuilder("evaluateBudgetAlertsStep", jobRepository)
            .tasklet({ _, _ ->
                val evaluated = evaluateBudgetAlerts.evaluateAll()
                log.info("evaluateBudgetAlertsJob run finished, evaluated={}", evaluated)
                RepeatStatus.FINISHED
            }, tx)
            .build()

    companion object {
        private val log = LoggerFactory.getLogger(EvaluateBudgetAlertsJobConfig::class.java)
    }
}
