package com.example.billing.bootstrap.config;

import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.common.bulkhead.configuration.ThreadPoolBulkheadConfigCustomizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BulkheadContextPropagationConfig} 의 빈 wiring 검증.
 *
 * <p>각 인스턴스 (pg / webhook / audit-export) 마다 customizer 빈이 정확히 한 개씩, 올바른 이름과
 * 함께 등록되었는지 확인. 통합 테스트 (Spring context 부팅) 는 BillingApplicationModulithTest 가
 * 부팅 자체로 cover — 여기서는 lightweight 단위 테스트만.</p>
 */
class BulkheadContextPropagationConfigTest {

    @Test
    void allBulkheadInstancesHaveDistinctCustomizers() {
        var config = new BulkheadContextPropagationConfig();
        var propagator = config.mdcContextPropagator();

        ThreadPoolBulkheadConfigCustomizer pg = config.pgBulkheadMdcCustomizer(propagator);
        ThreadPoolBulkheadConfigCustomizer webhook = config.webhookBulkheadMdcCustomizer(propagator);
        ThreadPoolBulkheadConfigCustomizer audit = config.auditExportBulkheadMdcCustomizer(propagator);

        // 인스턴스 이름 — yaml 의 인스턴스 키와 정확히 일치해야 매칭됨.
        assertThat(pg.name()).isEqualTo("pg");
        assertThat(webhook.name()).isEqualTo("webhook");
        assertThat(audit.name()).isEqualTo("audit-export");
    }

    @Test
    void customizer_addsContextPropagator_toBuilder() {
        var config = new BulkheadContextPropagationConfig();
        var propagator = config.mdcContextPropagator();
        var customizer = config.pgBulkheadMdcCustomizer(propagator);

        // builder 에 customizer 적용 후 build 한 ThreadPoolBulkheadConfig 의 propagators 에
        // 우리 propagator 가 들어가 있어야 함.
        ThreadPoolBulkheadConfig.Builder builder = ThreadPoolBulkheadConfig.custom();
        customizer.customize(builder);
        ThreadPoolBulkheadConfig built = builder.build();

        assertThat(built.getContextPropagator()).contains(propagator);
    }
}
