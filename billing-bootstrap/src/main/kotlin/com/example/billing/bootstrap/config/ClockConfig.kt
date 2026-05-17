package com.example.billing.bootstrap.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** [Clock] 빈 — 모든 도메인/서비스가 시간 결정 시 주입받음 (테스트 결정성). */
@Configuration
class ClockConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
