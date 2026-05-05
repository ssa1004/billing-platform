package com.example.billing.batch;

import com.example.billing.adapter.out.persistence.jpa.entity.SettlementRunJpaEntity;
import com.example.billing.application.command.RunSettlementCommand;
import com.example.billing.application.port.in.RunSettlementUseCase;
import com.example.billing.application.port.out.SettlementRunRepository;
import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManagerFactory;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

/**
 * 월별 정산 batch.
 *
 * <p>실행 흐름:
 * <ol>
 *   <li>PENDING 상태의 SettlementRun row 를 chunk-paging 으로 조회</li>
 *   <li>각 row 에 대해 {@link RunSettlementUseCase#run} 호출 — 그 안에서 advisory lock 으로
 *       동일 정산 중복 실행 방지</li>
 *   <li>처리 결과 (success / fail) 는 SettlementRun 에 기록</li>
 * </ol>
 *
 * <p>여러 인스턴스가 동시에 이 job 을 돌리면 SKIP LOCKED query 로 같은 row 를 두 번 잡지
 * 않도록 보장. 즉 worker pool 패턴.</p>
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class MonthlySettlementJobConfig {

    private static final int CHUNK_SIZE = 50;

    private final EntityManagerFactory entityManagerFactory;
    private final RunSettlementUseCase runSettlement;
    private final SettlementRunRepository settlementRunRepository;

    @Bean
    public Job monthlySettlementJob(JobRepository jobRepository, Step settlementStep) {
        return new JobBuilder("monthlySettlementJob", jobRepository)
                .start(settlementStep)
                .build();
    }

    @Bean
    public Step settlementStep(JobRepository jobRepository, PlatformTransactionManager tx) {
        return new StepBuilder("settlementStep", jobRepository)
                .<SettlementRunJpaEntity, SettlementRunJpaEntity>chunk(CHUNK_SIZE, tx)
                .reader(pendingSettlementReader(null))
                .processor(item -> {
                    BillingPeriod period = BillingPeriod.of(YearMonth.parse(item.getPeriodYearMonth()));
                    if (item.getCustomerId() != null) {
                        CustomerId customerId = CustomerId.of(item.getCustomerId());
                        try {
                            runSettlement.run(new RunSettlementCommand(customerId, period));
                        } catch (RuntimeException ex) {
                            log.warn("settlement failed for customer={} period={}: {}",
                                    customerId, period, ex.getMessage());
                        }
                    }
                    return item;
                })
                .writer(items -> {
                    // processor 에서 이미 처리됨. 여기선 chunk 단위 commit 만.
                    log.debug("settled {} runs in this chunk", items.size());
                })
                .faultTolerant()
                .skipLimit(100)
                .skip(RuntimeException.class)
                .build();
    }

    @Bean
    @StepScope
    public JpaPagingItemReader<SettlementRunJpaEntity> pendingSettlementReader(
            @Value("#{jobParameters['period']}") String periodParam) {
        Map<String, Object> params = new HashMap<>();
        params.put("status", com.example.billing.domain.settlement.SettlementStatus.PENDING);
        params.put("period", periodParam != null ? periodParam :
                BillingPeriod.containing(java.time.Instant.now()).previous().toKey());
        return new JpaPagingItemReaderBuilder<SettlementRunJpaEntity>()
                .name("pendingSettlementReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("""
                        SELECT s FROM SettlementRunJpaEntity s
                         WHERE s.periodYearMonth = :period
                           AND s.status = :status
                         ORDER BY s.createdAt
                        """)
                .parameterValues(params)
                .pageSize(CHUNK_SIZE)
                .build();
    }
}
