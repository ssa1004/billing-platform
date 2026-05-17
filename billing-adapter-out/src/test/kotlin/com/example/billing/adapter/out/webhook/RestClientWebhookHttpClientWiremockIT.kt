package com.example.billing.adapter.out.webhook

import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.webhook.WebhookDelivery
import com.example.billing.domain.webhook.WebhookEndpoint
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Webhook 발신 측 contract test — secret rotation grace 동안 두 secret 으로 서명한 두 값 을
 * 같은 헤더에 콤마로 결합해 보내는지 (ADR-0029).
 *
 * 검증 포인트:
 *  - rotation 안 한 endpoint → 헤더에 단일 서명 (콤마 없음).
 *  - rotation 직후 grace 안 → 헤더에 두 서명 (콤마 구분).
 *  - grace 만료 후 → 다시 단일 서명 (현재 secret 만).
 */
class RestClientWebhookHttpClientWiremockIT {

    @BeforeEach
    fun setUp() {
        wiremock.resetAll()
    }

    @Test
    fun send_singleSignature_whenNoRotation() {
        // rotation 안 한 endpoint — 헤더에 콤마 없는 단일 서명.
        val endpoint = endpointAt("/hook-1")
        wiremock.stubFor(post(urlEqualTo("/hook-1")).willReturn(aResponse().withStatus(200)))

        val client = newClient()
        client.send(endpoint, deliveryFor(endpoint, "InvoiceIssued", """{"id":1}"""))

        // 서명 헤더는 sha256= 으로 시작하는 단일 값 (콤마 없음).
        wiremock.verify(reqMatching("/hook-1", "X-Webhook-Signature", "^sha256=[0-9a-f]+$"))
    }

    @Test
    fun send_dualSignature_duringRotationGrace() {
        // rotation 직후 — endpoint 의 activeSecrets 는 [new, old]. 헤더에 두 서명을 콤마로 결합.
        val endpoint = endpointAt("/hook-2")
        endpoint.rotateSecret(CLOCK)
        wiremock.stubFor(post(urlEqualTo("/hook-2")).willReturn(aResponse().withStatus(200)))

        val client = newClient()
        client.send(endpoint, deliveryFor(endpoint, "PaymentSucceeded", """{"id":2}"""))

        // 헤더에 두 서명: "sha256=...new...,sha256=...old...".
        wiremock.verify(reqMatching("/hook-2", "X-Webhook-Signature", "^sha256=[0-9a-f]+,sha256=[0-9a-f]+$"))
    }

    @Test
    fun send_singleSignature_afterGraceExpires() {
        // grace 만료 후 — previousSecret 이 expire 되어 다시 단일 서명.
        val endpoint = endpointAt("/hook-3")
        endpoint.rotateSecret(CLOCK)

        // 발신 시점이 grace 만료 후 — clock 을 미래로.
        val pastGrace = Clock.fixed(NOW.plus(Duration.ofHours(25)), ZoneOffset.UTC)
        wiremock.stubFor(post(urlEqualTo("/hook-3")).willReturn(aResponse().withStatus(200)))

        val client = newClient(pastGrace)
        client.send(endpoint, deliveryFor(endpoint, "RefundProcessed", """{"id":3}"""))

        // grace 밖이면 다시 단일 서명.
        wiremock.verify(reqMatching("/hook-3", "X-Webhook-Signature", "^sha256=[0-9a-f]+$"))
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private fun newClient(clock: Clock = CLOCK): RestClientWebhookHttpClient =
        // RestClient 의 기본 builder + Wiremock baseUrl. webhook URL 은 endpoint.url() 의 절대 URL
        // 이라 baseUrl 은 사실상 무시되지만, RestClient.Builder 가 baseUrl 없이도 동작.
        RestClientWebhookHttpClient(RestClient.builder(), clock)

    private fun endpointAt(path: String): WebhookEndpoint = WebhookEndpoint.register(
        CustomerId.of("alice"),
        wiremock.baseUrl() + path,
        emptySet(),
        CLOCK,
    )

    private fun deliveryFor(endpoint: WebhookEndpoint, eventType: String, payload: String): WebhookDelivery =
        WebhookDelivery.schedule(endpoint.id, eventType, payload, CLOCK)

    private fun reqMatching(path: String, header: String, pattern: String): RequestPatternBuilder =
        postRequestedFor(urlEqualTo(path)).withHeader(header, matching(pattern))

    companion object {
        private val NOW: Instant = Instant.parse("2026-05-01T00:00:00Z")
        private val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

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
