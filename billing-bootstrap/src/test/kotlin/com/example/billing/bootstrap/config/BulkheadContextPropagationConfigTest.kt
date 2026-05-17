package com.example.billing.bootstrap.config

import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [BulkheadContextPropagationConfig] 의 빈 wiring 검증.
 *
 * 각 인스턴스 (pg / webhook / audit-export) 마다 customizer 빈이 정확히 한 개씩, 올바른 이름과
 * 함께 등록되었는지 확인. 통합 테스트 (Spring context 부팅) 는 BillingApplicationModulithTest 가
 * 부팅 자체로 cover — 여기서는 lightweight 단위 테스트만.
 */
class BulkheadContextPropagationConfigTest {

    @Test
    fun allBulkheadInstancesHaveDistinctCustomizers() {
        val config = BulkheadContextPropagationConfig()
        val propagator = config.mdcContextPropagator()

        val pg = config.pgBulkheadMdcCustomizer(propagator)
        val webhook = config.webhookBulkheadMdcCustomizer(propagator)
        val audit = config.auditExportBulkheadMdcCustomizer(propagator)

        // 인스턴스 이름 — yaml 의 인스턴스 키와 정확히 일치해야 매칭됨.
        assertThat(pg.name()).isEqualTo("pg")
        assertThat(webhook.name()).isEqualTo("webhook")
        assertThat(audit.name()).isEqualTo("audit-export")
    }

    @Test
    fun customizer_addsContextPropagator_toBuilder() {
        val config = BulkheadContextPropagationConfig()
        val propagator = config.mdcContextPropagator()
        val customizer = config.pgBulkheadMdcCustomizer(propagator)

        // builder 에 customizer 적용 후 build 한 ThreadPoolBulkheadConfig 의 propagators 에
        // 우리 propagator 가 들어가 있어야 함.
        val builder = ThreadPoolBulkheadConfig.custom()
        customizer.customize(builder)
        val built = builder.build()

        assertThat(built.contextPropagator).contains(propagator)
    }
}
