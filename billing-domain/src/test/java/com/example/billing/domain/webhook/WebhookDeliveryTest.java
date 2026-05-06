package com.example.billing.domain.webhook;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookDeliveryTest {

    private static final WebhookEndpointId ENDPOINT = WebhookEndpointId.newId();
    private static final Instant NOW = Instant.parse("2026-05-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static WebhookDelivery scheduled() {
        return WebhookDelivery.schedule(ENDPOINT, "InvoiceIssued", "{\"invoiceId\":\"inv-1\"}", CLOCK);
    }

    @Test
    void schedule_initialState_isPendingNowZeroAttempts() {
        var d = scheduled();
        assertThat(d.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(d.attemptCount()).isZero();
        assertThat(d.nextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void successFlow_oneShot() {
        var d = scheduled();
        d.beginAttempt(CLOCK);
        d.markSuccess(200, CLOCK);
        assertThat(d.status()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
        assertThat(d.attemptCount()).isEqualTo(1);
        assertThat(d.deliveredAt()).isEqualTo(NOW);
    }

    @Test
    void retryable_schedulesNextAttemptWithBackoff() {
        var d = scheduled();
        // 1차 실패 → 1분 후 재시도
        d.beginAttempt(CLOCK);
        d.markRetryable(503, "service unavailable", CLOCK);
        assertThat(d.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(d.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(d.attemptCount()).isEqualTo(1);

        // 2차 실패 → 5분 후
        var clock2 = Clock.fixed(NOW.plus(Duration.ofMinutes(1)), ZoneOffset.UTC);
        d.beginAttempt(clock2);
        d.markRetryable(503, "still down", clock2);
        assertThat(d.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)).plus(Duration.ofMinutes(5)));
        assertThat(d.attemptCount()).isEqualTo(2);
    }

    @Test
    void retryable_afterMaxAttempts_movesToDeadLettered() {
        var d = scheduled();
        // MAX_ATTEMPTS = 5 — 5번 모두 실패하면 5번째 markRetryable 에서 DEAD 로
        Clock c = CLOCK;
        for (int i = 0; i < WebhookDelivery.MAX_ATTEMPTS; i++) {
            d.beginAttempt(c);
            d.markRetryable(503, "down", c);
            c = Clock.fixed(c.instant().plusSeconds(1), ZoneOffset.UTC);
        }
        assertThat(d.status()).isEqualTo(WebhookDeliveryStatus.DEAD_LETTERED);
        assertThat(d.attemptCount()).isEqualTo(WebhookDelivery.MAX_ATTEMPTS);
    }

    @Test
    void markDead_immediatelyDeadLettered() {
        var d = scheduled();
        d.beginAttempt(CLOCK);
        d.markDead(404, "url not found", CLOCK);
        assertThat(d.status()).isEqualTo(WebhookDeliveryStatus.DEAD_LETTERED);
        assertThat(d.attemptCount()).isEqualTo(1);
        assertThat(d.lastResponseStatus()).isEqualTo(404);
    }

    @Test
    void markSuccess_onlyFromInFlight() {
        var d = scheduled();   // PENDING
        assertThatThrownBy(() -> d.markSuccess(200, CLOCK))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_resetsToPendingWithOneAttemptLeft() {
        var d = scheduled();
        d.beginAttempt(CLOCK);
        d.markDead(500, "x", CLOCK);
        assertThat(d.status()).isEqualTo(WebhookDeliveryStatus.DEAD_LETTERED);

        d.replay(CLOCK);
        assertThat(d.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        // attemptCount 가 (MAX-1) 로 낮춰져 1번 추가 시도 보장
        assertThat(d.attemptCount()).isEqualTo(WebhookDelivery.MAX_ATTEMPTS - 1);
    }

    @Test
    void replay_onlyFromDeadLettered() {
        var d = scheduled();
        assertThatThrownBy(() -> d.replay(CLOCK)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lastError_truncatedTo256Chars() {
        var d = scheduled();
        d.beginAttempt(CLOCK);
        String huge = "x".repeat(1000);
        d.markRetryable(500, huge, CLOCK);
        assertThat(d.lastError()).hasSize(256);
    }
}
