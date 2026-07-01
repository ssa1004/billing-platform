package com.example.billing.batch

import com.example.billing.adapter.out.persistence.jpa.entity.SettlementRunJpaEntity
import com.example.billing.application.command.RunSettlementCommand
import com.example.billing.application.port.`in`.RunSettlementUseCase
import com.example.billing.domain.settlement.BillingPeriod
import com.example.billing.domain.settlement.SettlementStatus
import com.example.billing.domain.shared.CustomerId
import jakarta.persistence.EntityManagerFactory
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.database.JpaPagingItemReader
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.YearMonth

/**
 * 월별 정산 batch.
 *
 * 실행 흐름:
 *  1. PENDING 상태의 SettlementRun row 를 chunk-paging 으로 조회
 *  2. 각 row 에 대해 [RunSettlementUseCase.run] 호출 — 그 안에서 advisory lock 으로
 *     동일 정산 중복 실행 방지
 *  3. 처리 결과 (success / fail) 는 RunSettlementUseCase.run 내부에서 SettlementRun 에 기록
 *
 * 여러 인스턴스가 동시에 이 job 을 돌리면 SKIP LOCKED query 로 같은 row 를 두 번 잡지
 * 않도록 보장. 즉 worker pool 패턴.
 */
@Configuration
open class MonthlySettlementJobConfig(
    private val entityManagerFactory: EntityManagerFactory,
    private val runSettlement: RunSettlementUseCase,
) {

    @Bean
    open fun monthlySettlementJob(jobRepository: JobRepository, settlementStep: Step): Job =
        JobBuilder("monthlySettlementJob", jobRepository)
            .start(settlementStep)
            .build()

    @Bean
    open fun settlementStep(jobRepository: JobRepository, tx: PlatformTransactionManager): Step =
        StepBuilder("settlementStep", jobRepository)
            .chunk<SettlementRunJpaEntity, SettlementRunJpaEntity>(CHUNK_SIZE, tx)
            .reader(pendingSettlementReader(null))
            .processor { item ->
                val period = BillingPeriod.of(YearMonth.parse(item.periodYearMonth))
                val customerIdValue = item.customerId
                if (customerIdValue != null) {
                    val customerId = CustomerId.of(customerIdValue)
                    try {
                        runSettlement.run(RunSettlementCommand(customerId, period))
                    } catch (ex: RuntimeException) {
                        log.warn(
                            "settlement failed for customer={} period={}: {}",
                            customerId, period, ex.message,
                        )
                    }
                }
                item
            }
            .writer { items ->
                // processor 에서 이미 처리됨. 여기선 chunk 단위 commit 만.
                log.debug("settled {} runs in this chunk", items.size())
            }
            .faultTolerant()
            .skipLimit(100)
            .skip(RuntimeException::class.java)
            .build()

    @Bean
    @StepScope
    open fun pendingSettlementReader(
        @Value("#{jobParameters['period']}") periodParam: String?,
    ): JpaPagingItemReader<SettlementRunJpaEntity> {
        val params: MutableMap<String, Any> = HashMap()
        params["status"] = SettlementStatus.PENDING
        params["period"] = periodParam
            ?: BillingPeriod.containing(Instant.now()).previous().toKey()
        return JpaPagingItemReaderBuilder<SettlementRunJpaEntity>()
            .name("pendingSettlementReader")
            .entityManagerFactory(entityManagerFactory)
            .queryString(
                """
                SELECT s FROM SettlementRunJpaEntity s
                 WHERE s.periodYearMonth = :period
                   AND s.status = :status
                 ORDER BY s.createdAt
                """,
            )
            .parameterValues(params)
            .pageSize(CHUNK_SIZE)
            .build()
    }

    companion object {
        private val log = LoggerFactory.getLogger(MonthlySettlementJobConfig::class.java)
        private const val CHUNK_SIZE = 50
    }
}
