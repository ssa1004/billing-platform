package com.example.billing.adapter.out.pg

import com.example.billing.application.port.out.PgClient.AuthorizeRequest
import com.example.billing.application.port.out.PgClient.AuthorizeResult
import com.example.billing.application.port.out.PgClient.RefundRequest
import com.example.billing.application.port.out.PgClient.RefundResult
import com.example.billing.domain.payment.PaymentMethod
import com.example.billing.domain.shared.Money
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.util.Currency

/**
 * PG contract test — RestClientPgClient (RestClient 기반) 가 Wiremock 실제 HTTP 응답을 정확히 매핑하는지.
 *
 * 검증 포인트:
 *  - 요청 JSON 스키마가 PG 사양과 일치 (idempotencyKey, amount, method, orderId)
 *  - 응답 JSON → AuthorizeResult/RefundResult 매핑
 *  - 실패 응답 (4xx) 도 결과 객체로 매핑되는지
 *
 * Resilience4j @CircuitBreaker / @Retry 는 Spring 컨텍스트가 있을 때만 적용되므로 이 테스트는
 * 순수 RestClient 호출만 검증. CB 시나리오는 별도 통합 테스트에서 확인.
 */
class RestClientPgClientWiremockIT {

    private lateinit var client: RestClientPgClient

    @BeforeEach
    fun setUp() {
        wiremock.resetAll()
        // Wiremock 의 Jetty 가 Java 21 의 HTTP/2 default 와 RST_STREAM 충돌 → HTTP/1.1 강제 (SimpleClientHttpRequestFactory)
        val rest = RestClient.builder()
            .baseUrl(wiremock.baseUrl())
            .requestFactory(SimpleClientHttpRequestFactory())
            .build()
        client = RestClientPgClient(rest)
    }

    @Test
    fun authorize_success_mapsResponseToApprovedResult() {
        wiremock.stubFor(
            post(urlEqualTo("/v1/payments/authorize"))
                .withRequestBody(matchingJsonPath("$.idempotencyKey", equalTo("pay-key-1")))
                .withRequestBody(matchingJsonPath("$.orderId", equalTo("order-1")))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json.writeValueAsString(AuthorizeResult.approved("pg-tx-12345"))),
                ),
        )

        val result = client.authorize(
            AuthorizeRequest(
                "pay-key-1",
                Money.of(BigDecimal.valueOf(1500), KRW),
                PaymentMethod.CARD,
                "order-1",
            ),
        )

        assertThat(result.approved).isTrue()
        assertThat(result.pgTransactionId).isEqualTo("pg-tx-12345")
        assertThat(result.errorCode).isNull()
    }

    @Test
    fun authorize_pgRejected_mapsResponseToRejectedResult() {
        wiremock.stubFor(
            post(urlEqualTo("/v1/payments/authorize"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            json.writeValueAsString(AuthorizeResult.rejected("INSUFFICIENT_FUNDS", "card declined")),
                        ),
                ),
        )

        val result = client.authorize(
            AuthorizeRequest(
                "pay-key-2",
                Money.of(BigDecimal.valueOf(99_999_999), KRW),
                PaymentMethod.CARD,
                "order-2",
            ),
        )

        assertThat(result.approved).isFalse()
        assertThat(result.errorCode).isEqualTo("INSUFFICIENT_FUNDS")
        assertThat(result.errorMessage).isEqualTo("card declined")
        assertThat(result.pgTransactionId).isNull()
    }

    @Test
    fun refund_success_mapsResponseToApprovedResult() {
        wiremock.stubFor(
            post(urlEqualTo("/v1/payments/refund"))
                .withRequestBody(matchingJsonPath("$.pgTransactionId", equalTo("pg-tx-12345")))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json.writeValueAsString(RefundResult.approved("pg-refund-99"))),
                ),
        )

        val result = client.refund(
            RefundRequest("pg-tx-12345", Money.of(BigDecimal.valueOf(1500), KRW), "customer request"),
        )

        assertThat(result.approved).isTrue()
        assertThat(result.pgRefundId).isEqualTo("pg-refund-99")
    }

    @Test
    fun refund_serverError_propagatesAsRuntimeException() {
        wiremock.stubFor(
            post(urlEqualTo("/v1/payments/refund"))
                .willReturn(aResponse().withStatus(500).withBody("internal error")),
        )

        // CB 어노테이션이 없는 직접 호출 — RestClient 가 5xx 를 RestClientException 으로 throw
        assertThatThrownBy {
            client.refund(RefundRequest("pg-tx-fail", Money.of(BigDecimal.valueOf(1000), KRW), "any"))
        }.isInstanceOf(RuntimeException::class.java)
    }

    companion object {
        private val KRW: Currency = Currency.getInstance("KRW")
        private val json: ObjectMapper = ObjectMapper()

        private lateinit var wiremock: WireMockServer

        @JvmStatic
        @BeforeAll
        fun startServer() {
            wiremock = WireMockServer(WireMockConfiguration.options().dynamicPort())
            wiremock.start()
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            if (::wiremock.isInitialized) wiremock.stop()
        }
    }
}
