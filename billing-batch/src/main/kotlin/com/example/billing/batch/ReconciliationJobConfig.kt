package com.example.billing.batch

import com.example.billing.adapter.out.persistence.jpa.entity.WalletJpaEntity
import jakarta.persistence.EntityManagerFactory
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemReader
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * 일일 정산 — 모든 Wallet 을 페이징하여 ledger_entries 합계와 wallet.balance 가 일치하는지 검증.
 *
 * 실패한 Wallet 은 로그 + 알림. 자동 보정은 운영자 수동 결정 (정합성 깨짐은 보통 코드 버그 시그널).
 */
@Configuration
open class ReconciliationJobConfig(
    private val entityManagerFactory: EntityManagerFactory,
) {

    @Bean
    open fun dailyReconciliationJob(jobRepository: JobRepository, reconciliationStep: Step): Job =
        JobBuilder("dailyReconciliationJob", jobRepository)
            .start(reconciliationStep)
            .build()

    @Bean
    open fun reconciliationStep(jobRepository: JobRepository, tx: PlatformTransactionManager): Step =
        StepBuilder("reconciliationStep", jobRepository)
            .chunk<WalletJpaEntity, WalletJpaEntity>(100, tx)
            .reader(walletReader())
            .writer(reconciliationWriter())
            .faultTolerant()
            .skipLimit(10)
            .skip(IllegalStateException::class.java)
            .build()

    @Bean
    open fun walletReader(): ItemReader<WalletJpaEntity> =
        JpaPagingItemReaderBuilder<WalletJpaEntity>()
            .name("walletReader")
            .entityManagerFactory(entityManagerFactory)
            .queryString("SELECT w FROM WalletJpaEntity w ORDER BY w.id")
            .pageSize(100)
            .build()

    @Bean
    open fun reconciliationWriter(): ItemWriter<WalletJpaEntity> = ItemWriter { wallets ->
        // 단순 — 실 운영은 ledger 합계 vs balance 비교 후 불일치만 별도 테이블 INSERT
        wallets.forEach { w ->
            log.info(
                "[reconciliation] wallet id={} owner={} balance={} (ledger 합계 검증은 v2 보강)",
                w.id, w.ownerId, w.balance,
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ReconciliationJobConfig::class.java)
    }
}
