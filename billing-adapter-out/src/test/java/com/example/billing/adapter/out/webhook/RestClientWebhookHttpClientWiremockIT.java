package com.example.billing.adapter.out.webhook;

import com.example.billing.application.port.out.WebhookHttpClient;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.webhook.WebhookDelivery;
import com.example.billing.domain.webhook.WebhookEndpoint;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Webhook 발신 측 contract test — secret rotation grace 동안 두 secret 으로 서명한 두 값 을
 * 같은 헤더에 콤마로 결합해 보내는지 (ADR-0029).
 *
 * <p>검증 포인트:</p>
 * <ul>
 *   <li>rotation 안 한 endpoint → 헤더에 단일 서명 (콤마 없음).</li>
 *   <li>rotation 직후 grace 안 → 헤더에 두 서명 (콤마 구분).</li>
 *   <li>grace 만료 후 → 다시 단일 서명 (현재 secret 만).</li>
 * </ul>
 */
class RestClientWebhookHttpClientWiremockIT {

    private static WireMockServer wiremock;
    private static final Instant NOW = Instant.parse("2026-05-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

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
    }

    @Test
    void send_singleSignature_whenNoRotation() {
        // rotation 안 한 endpoint — 헤더에 콤마 없는 단일 서명.
        var endpoint = endpointAt("/hook-1");
        wiremock.stubFor(post(urlEqualTo("/hook-1"))
                .willReturn(aResponse().withStatus(200)));

        var client = newClient();
        client.send(endpoint, deliveryFor(endpoint, "InvoiceIssued", "{\"id\":1}"));

        // 서명 헤더는 sha256= 으로 시작하는 단일 값 (콤마 없음).
        wiremock.verify(reqMatching("/hook-1",
                "X-Webhook-Signature", "^sha256=[0-9a-f]+$"));
    }

    @Test
    void send_dualSignature_duringRotationGrace() {
        // rotation 직후 — endpoint 의 activeSecrets 는 [new, old]. 헤더에 두 서명을 콤마로 결합.
        var endpoint = endpointAt("/hook-2");
        endpoint.rotateSecret(CLOCK);
        wiremock.stubFor(post(urlEqualTo("/hook-2"))
                .willReturn(aResponse().withStatus(200)));

        var client = newClient();
        client.send(endpoint, deliveryFor(endpoint, "PaymentSucceeded", "{\"id\":2}"));

        // 헤더에 두 서명: "sha256=...new...,sha256=...old...".
        wiremock.verify(reqMatching("/hook-2",
                "X-Webhook-Signature", "^sha256=[0-9a-f]+,sha256=[0-9a-f]+$"));
    }

    @Test
    void send_singleSignature_afterGraceExpires() {
        // grace 만료 후 — previousSecret 이 expire 되어 다시 단일 서명.
        var endpoint = endpointAt("/hook-3");
        endpoint.rotateSecret(CLOCK);

        // 발신 시점이 grace 만료 후 — clock 을 미래로.
        Clock pastGrace = Clock.fixed(NOW.plus(Duration.ofHours(25)), ZoneOffset.UTC);
        wiremock.stubFor(post(urlEqualTo("/hook-3"))
                .willReturn(aResponse().withStatus(200)));

        var client = newClient(pastGrace);
        client.send(endpoint, deliveryFor(endpoint, "RefundProcessed", "{\"id\":3}"));

        // grace 밖이면 다시 단일 서명.
        wiremock.verify(reqMatching("/hook-3",
                "X-Webhook-Signature", "^sha256=[0-9a-f]+$"));
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private RestClientWebhookHttpClient newClient() {
        return newClient(CLOCK);
    }

    private RestClientWebhookHttpClient newClient(Clock clock) {
        // RestClient 의 기본 builder + Wiremock baseUrl. webhook URL 은 endpoint.url() 의 절대 URL
        // 이라 baseUrl 은 사실상 무시되지만, RestClient.Builder 가 baseUrl 없이도 동작.
        return new RestClientWebhookHttpClient(RestClient.builder(), clock);
    }

    private static WebhookEndpoint endpointAt(String path) {
        return WebhookEndpoint.register(
                CustomerId.of("alice"),
                wiremock.baseUrl() + path,
                Set.of(),
                CLOCK);
    }

    private static WebhookDelivery deliveryFor(WebhookEndpoint endpoint, String eventType, String payload) {
        return WebhookDelivery.schedule(endpoint.id(), eventType, payload, CLOCK);
    }

    private static RequestPatternBuilder reqMatching(String path, String header, String pattern) {
        return postRequestedFor(urlEqualTo(path))
                .withHeader(header, matching(pattern));
    }

    /** WiremockIT 끼리 충돌 방지용 — UUID 만들 때 deterministic 하지 않아도 OK. */
    @SuppressWarnings("unused")
    private static String randomId() {
        return UUID.randomUUID().toString();
    }
}
