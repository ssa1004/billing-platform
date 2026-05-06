package com.example.billing.adapter.out.pg;

import com.example.billing.application.port.out.PgClient;
import com.example.billing.domain.payment.PaymentMethod;
import com.example.billing.domain.shared.Money;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Currency;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resilience4j Circuit Breaker 시나리오 — PG 가 연속 실패 시 CB 가 OPEN 으로 전이하고
 * fallback 메서드가 호출되는지 검증.
 *
 * <p>CB 설정 (테스트 전용 — application.yml 보다 작은 임계치):
 * minimumNumberOfCalls=4, slidingWindowSize=4, failureRateThreshold=50% → 4번 중 2번 실패 시 OPEN.</p>
 *
 * <p>검증 시나리오:</p>
 * <ol>
 *   <li>PG 에 5xx 응답 → Retry 가 3번까지 시도 (CB 메트릭은 retry 마지막 결과만 기록)</li>
 *   <li>여러 호출 후 CB 가 OPEN 으로 전이</li>
 *   <li>OPEN 상태에서 다음 호출은 PG 에 도달하지 않고 fallback (CB_OPEN) 반환</li>
 * </ol>
 */
@SpringBootTest(classes = RestClientPgClientCircuitBreakerIT.TestApp.class)
class RestClientPgClientCircuitBreakerIT {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static WireMockServer wiremock;

    @BeforeAll
    static void startWiremock() {
        wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stopWiremock() {
        if (wiremock != null) wiremock.stop();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("billing.pg.enabled", () -> "true");
        r.add("billing.pg.base-url", () -> wiremock.baseUrl());
        // 테스트 전용 — 작은 임계치로 빨리 OPEN
        r.add("resilience4j.circuitbreaker.instances.pg.slidingWindowSize", () -> "4");
        r.add("resilience4j.circuitbreaker.instances.pg.minimumNumberOfCalls", () -> "4");
        r.add("resilience4j.circuitbreaker.instances.pg.failureRateThreshold", () -> "50");
        r.add("resilience4j.circuitbreaker.instances.pg.waitDurationInOpenState", () -> "30s");
        r.add("resilience4j.retry.instances.pg.maxAttempts", () -> "1");
    }

    @Autowired RestClientPgClient client;
    @Autowired CircuitBreakerRegistry cbRegistry;

    @BeforeEach
    void resetCb() {
        wiremock.resetAll();
        cbRegistry.circuitBreaker("pg").reset();
    }

    @Test
    void afterRepeatedFailures_circuitBreakerOpens_andFallbackKicksIn() {
        // PG 가 항상 5xx
        wiremock.stubFor(post(urlEqualTo("/v1/payments/authorize"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        var req = new PgClient.AuthorizeRequest(
                "key-fail", Money.of(BigDecimal.valueOf(1000), KRW),
                PaymentMethod.CARD, "order-fail");

        // minimumNumberOfCalls=4 만큼 호출하면 CB 가 OPEN
        for (int i = 0; i < 4; i++) {
            var r = client.authorize(req);
            assertThat(r.approved()).isFalse(); // fallback 또는 직접 실패 모두 rejected
        }

        CircuitBreaker cb = cbRegistry.circuitBreaker("pg");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // OPEN 상태 확인 후 추가 호출 → fallback 의 CB_OPEN 반환
        wiremock.resetAll(); // 이번엔 stub 없음 — PG 도달 시 404가 되므로 fallback이 PG 호출 안한 게 검증됨
        var fallback = client.authorize(req);
        assertThat(fallback.approved()).isFalse();
        assertThat(fallback.errorCode()).isEqualTo("CB_OPEN");
        // fallback 호출 시 wiremock 으로의 새 요청은 발생하지 않아야 함
        assertThat(wiremock.getAllServeEvents()).isEmpty();
    }

    @Test
    void successfulCalls_keepCircuitClosed() {
        wiremock.stubFor(post(urlEqualTo("/v1/payments/authorize"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"approved\":true,\"pgTransactionId\":\"ok-tx\",\"errorCode\":null,\"errorMessage\":null}")));

        var req = new PgClient.AuthorizeRequest(
                "key-ok", Money.of(BigDecimal.valueOf(500), KRW),
                PaymentMethod.CARD, "order-ok");

        for (int i = 0; i < 5; i++) {
            var r = client.authorize(req);
            assertThat(r.approved()).isTrue();
        }

        CircuitBreaker cb = cbRegistry.circuitBreaker("pg");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * 최소한의 Spring Boot 컨텍스트 — Resilience4j auto-config + AOP 만 활성화.
     * MockPgClient 는 billing.pg.enabled=true 라 자동 비활성, RestClientPgClient 만 활성.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackageClasses = RestClientPgClient.class)
    static class TestApp {

        @Bean
        RestClient pgRestClient() {
            return RestClient.builder()
                    .baseUrl(wiremock.baseUrl())
                    .requestFactory(new SimpleClientHttpRequestFactory())
                    .build();
        }
    }
}
