package com.example.billing.bootstrap.config

import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig
import io.github.resilience4j.common.bulkhead.configuration.ThreadPoolBulkheadConfigCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Resilience4j 의 모든 ThreadPoolBulkhead 인스턴스에 [MdcContextPropagator] 를 자동 등록.
 *
 * **왜 이 방식**: yaml 의 `resilience4j.thread-pool-bulkhead.instances.*` 설정은 그대로
 * 두고 (pool size / queue capacity 등 운영 변수), context propagator 만 코드로 합쳐 넣음.
 * Resilience4j Spring Boot autoconfigure 가 ThreadPoolBulkheadRegistry 빈을 만들 때 발견된 모든
 * [ThreadPoolBulkheadConfigCustomizer] 를 이름 매칭으로 적용 — 우리는 인스턴스별로 빈을 한 개씩
 * 등록.
 *
 * **왜 인스턴스별로 따로 빈을 만드나**: [ThreadPoolBulkheadConfigCustomizer.name] 이
 * 정확히 한 인스턴스만 매칭. `"pg"` customizer 는 `"webhook"` bulkhead 에 적용 안 됨.
 * 인스턴스가 늘어나면 (예: ADR-0026 의 후속으로 settlement-export 추가) 여기에 빈 한 개 더 추가.
 *
 * **모든 인스턴스가 같은 propagator**: MDC 전파는 도메인 무관 — PG / webhook / audit-export
 * 어느 worker 든 caller 의 trace context 를 그대로 받으면 됨. 단일 propagator 인스턴스 공유.
 *
 * ADR-0027 참고.
 */
@Configuration
class BulkheadContextPropagationConfig {

    /**
     * 단일 propagator 인스턴스 — stateless 라 모든 customizer 가 공유 안전.
     */
    @Bean
    fun mdcContextPropagator(): MdcContextPropagator = MdcContextPropagator()

    /**
     * 각 bulkhead 인스턴스마다 propagator 를 추가하는 customizer 빈.
     *
     * [ThreadPoolBulkheadConfig.Builder.contextPropagator] 는 가변 인자 — 다른 propagator
     * (예: SecurityContextPropagator, TenantContextPropagator) 를 추후 추가하려면 여기서 같이
     * 넘기면 됩니다.
     */
    @Bean
    fun pgBulkheadMdcCustomizer(propagator: MdcContextPropagator): ThreadPoolBulkheadConfigCustomizer =
        ThreadPoolBulkheadConfigCustomizer.of("pg") { builder ->
            builder.contextPropagator(propagator)
        }

    @Bean
    fun webhookBulkheadMdcCustomizer(propagator: MdcContextPropagator): ThreadPoolBulkheadConfigCustomizer =
        ThreadPoolBulkheadConfigCustomizer.of("webhook") { builder ->
            builder.contextPropagator(propagator)
        }

    @Bean
    fun auditExportBulkheadMdcCustomizer(propagator: MdcContextPropagator): ThreadPoolBulkheadConfigCustomizer =
        ThreadPoolBulkheadConfigCustomizer.of("audit-export") { builder ->
            builder.contextPropagator(propagator)
        }

    companion object {
        /** Bulkhead 인스턴스 이름 — yaml 의 `resilience4j.thread-pool-bulkhead.instances.<name>` 와 일치. */
        private val BULKHEAD_NAMES: List<String> = listOf("pg", "webhook", "audit-export")

        /**
         * 등록된 인스턴스 이름 목록 — 통합 테스트가 yaml 의 인스턴스와 일치 검증할 때 사용.
         */
        @JvmStatic
        internal fun bulkheadNames(): List<String> = BULKHEAD_NAMES
    }
}
