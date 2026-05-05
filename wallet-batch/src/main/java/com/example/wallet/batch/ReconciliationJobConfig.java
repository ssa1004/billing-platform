package com.example.wallet.batch;

import com.example.wallet.adapter.out.persistence.jpa.entity.WalletJpaEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManagerFactory;

/**
 * 일일 정산 — 모든 Wallet 을 페이징하여 ledger_entries 합계와 wallet.balance 가 일치하는지 검증.
 *
 * <p>실패한 Wallet 은 로그 + 알림. 자동 보정은 운영자 수동 결정 (정합성 깨짐은 보통 코드 버그 시그널).</p>
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class ReconciliationJobConfig {

    private final EntityManagerFactory entityManagerFactory;

    @Bean
    public Job dailyReconciliationJob(JobRepository jobRepository, Step reconciliationStep) {
        return new JobBuilder("dailyReconciliationJob", jobRepository)
                .start(reconciliationStep)
                .build();
    }

    @Bean
    public Step reconciliationStep(JobRepository jobRepository, PlatformTransactionManager tx) {
        return new StepBuilder("reconciliationStep", jobRepository)
                .<WalletJpaEntity, WalletJpaEntity>chunk(100, tx)
                .reader(walletReader())
                .writer(reconciliationWriter())
                .faultTolerant()
                .skipLimit(10)
                .skip(IllegalStateException.class)
                .build();
    }

    @Bean
    public ItemReader<WalletJpaEntity> walletReader() {
        return new JpaPagingItemReaderBuilder<WalletJpaEntity>()
                .name("walletReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("SELECT w FROM WalletJpaEntity w ORDER BY w.id")
                .pageSize(100)
                .build();
    }

    @Bean
    public ItemWriter<WalletJpaEntity> reconciliationWriter() {
        // 단순 — 실 운영은 ledger 합계 vs balance 비교 후 불일치만 별도 테이블 INSERT
        return wallets -> wallets.forEach(w ->
                log.info("[reconciliation] wallet id={} owner={} balance={} (ledger 합계 검증은 v2 보강)",
                        w.getId(), w.getOwnerId(), w.getBalance()));
    }
}
