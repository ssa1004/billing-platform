package com.example.billing.batch.scheduler

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import javax.sql.DataSource

/**
 * ShedLock 활성화 — multi-instance 환경에서 같은 @Scheduled 메서드가 동시에 실행되지 않도록
 * 분산 lock 을 적용한다.
 *
 * `defaultLockAtMostFor` 는 lock holder Pod 가 죽었을 때 다음 Pod 가 락을 가져갈 수
 * 있는 최대 시간. 너무 짧으면 작업 중 다른 Pod 가 끼어들고, 너무 길면 죽은 Pod 의 락이
 * 오래 남는다. 본 시스템의 가장 긴 batch (월별 정산) 가 30분 안에 끝나는 것을 가정하여
 * 1시간으로 설정.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT1H")
@Profile("prod", "scheduler")
open class ShedLockConfig {

    @Bean
    open fun lockProvider(dataSource: DataSource): LockProvider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(JdbcTemplate(dataSource))
                .usingDbTime()  // DB 시계 기준 (Pod 간 시계 drift 회피)
                .build(),
        )
}
