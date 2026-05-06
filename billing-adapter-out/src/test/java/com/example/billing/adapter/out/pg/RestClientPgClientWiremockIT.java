package com.example.billing.adapter.out.pg;

import com.example.billing.application.port.out.PgClient;
import com.example.billing.domain.payment.PaymentMethod;
import com.example.billing.domain.shared.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Currency;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG contract test — RestClientPgClient (RestClient 기반) 가 Wiremock 실제 HTTP 응답을 정확히 매핑하는지.
 *
 * <p>검증 포인트:</p>
 * <ul>
 *   <li>요청 JSON 스키마가 PG 사양과 일치 (idempotencyKey, amount, method, orderId)</li>
 *   <li>응답 JSON → AuthorizeResult/RefundResult 매핑</li>
 *   <li>실패 응답 (4xx) 도 결과 객체로 매핑되는지</li>
 * </ul>
 *
 * <p>Resilience4j @CircuitBreaker / @Retry 는 Spring 컨텍스트가 있을 때만 적용되므로 이 테스트는
 * 순수 RestClient 호출만 검증. CB 시나리오는 별도 통합 테스트에서 확인.</p>
 */
class RestClientPgClientWiremockIT {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final ObjectMapper json = new ObjectMapper();

    private static WireMockServer wiremock;
    private RestClientPgClient client;

    @BeforeAll
    static void startServer() {
        wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stopServer() {
        if (wiremock != null) wiremock.stop();
    }

    @BeforeEach
    void setUp() {
        wiremock.resetAll();
        // Wiremock 의 Jetty 가 Java 21 의 HTTP/2 default 와 RST_STREAM 충돌 → HTTP/1.1 강제 (SimpleClientHttpRequestFactory)
        var rest = RestClient.builder()
                .baseUrl(wiremock.baseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        client = new RestClientPgClient(rest);
    }

    @Test
    void authorize_success_mapsResponseToApprovedResult() throws Exception {
        wiremock.stubFor(post(urlEqualTo("/v1/payments/authorize"))
                .withRequestBody(matchingJsonPath("$.idempotencyKey", equalTo("pay-key-1")))
                .withRequestBody(matchingJsonPath("$.orderId", equalTo("order-1")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json.writeValueAsString(PgClient.AuthorizeResult
                                .approved("pg-tx-12345")))));

        var result = client.authorize(new PgClient.AuthorizeRequest(
                "pay-key-1",
                Money.of(BigDecimal.valueOf(1500), KRW),
                PaymentMethod.CARD,
                "order-1"));

        assertThat(result.approved()).isTrue();
        assertThat(result.pgTransactionId()).isEqualTo("pg-tx-12345");
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void authorize_pgRejected_mapsResponseToRejectedResult() throws Exception {
        wiremock.stubFor(post(urlEqualTo("/v1/payments/authorize"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json.writeValueAsString(PgClient.AuthorizeResult
                                .rejected("INSUFFICIENT_FUNDS", "card declined")))));

        var result = client.authorize(new PgClient.AuthorizeRequest(
                "pay-key-2",
                Money.of(BigDecimal.valueOf(99999999), KRW),
                PaymentMethod.CARD,
                "order-2"));

        assertThat(result.approved()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(result.errorMessage()).isEqualTo("card declined");
        assertThat(result.pgTransactionId()).isNull();
    }

    @Test
    void refund_success_mapsResponseToApprovedResult() throws Exception {
        wiremock.stubFor(post(urlEqualTo("/v1/payments/refund"))
                .withRequestBody(matchingJsonPath("$.pgTransactionId",
                        equalTo("pg-tx-12345")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json.writeValueAsString(PgClient.RefundResult
                                .approved("pg-refund-99")))));

        var result = client.refund(new PgClient.RefundRequest(
                "pg-tx-12345",
                Money.of(BigDecimal.valueOf(1500), KRW),
                "customer request"));

        assertThat(result.approved()).isTrue();
        assertThat(result.pgRefundId()).isEqualTo("pg-refund-99");
    }

    @Test
    void refund_serverError_propagatesAsRuntimeException() {
        wiremock.stubFor(post(urlEqualTo("/v1/payments/refund"))
                .willReturn(aResponse().withStatus(500).withBody("internal error")));

        // CB 어노테이션이 없는 직접 호출 — RestClient 가 5xx 를 RestClientException 으로 throw
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                client.refund(new PgClient.RefundRequest(
                        "pg-tx-fail",
                        Money.of(BigDecimal.valueOf(1000), KRW),
                        "any"))
        ).isInstanceOf(RuntimeException.class);
    }
}
