package com.example.billing.bootstrap.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * DLQ admin (ADR-0033 — notification-hub ADR-0015 패턴 이식) 의 비동기 worker pool.
 *
 * <p>{@code dlqBulkExecutor} — DLQ bulk-replay / bulk-discard 비동기 worker 전용. 작은 pool
 * (core 1 / max 2) 로 동시 실행 1건만 허용 — PG / Kafka / Outbox 폭주 방지. concurrency 늘리는
 * 건 cluster 단에서 다른 pod 가 받게 두는 방향.
 *
 * <p>{@code billing-application} 의 {@link com.example.billing.application.service.DlqBulkAdminService}
 * 가 {@code @Qualifier("dlqBulkExecutor")} 로 주입.
 *
 * <p>queue capacity 8 — bulk 작업이 들어왔는데 worker 가 다 차 있으면 9번째 호출은 caller-runs
 * 정책 (기본) 으로 호출 thread (controller) 에서 실행되어 controller 응답이 지연됨. 위험하니
 * 의도적으로 small (대신 controller 단 rate-limit `admin:dlq:bulk` 가 분당 60으로 차단).
 */
@Configuration
public class DlqAdminConfig {

    @Bean("dlqBulkExecutor")
    public Executor dlqBulkExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("dlq-bulk-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
