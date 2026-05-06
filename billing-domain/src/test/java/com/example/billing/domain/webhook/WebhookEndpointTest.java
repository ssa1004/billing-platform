package com.example.billing.domain.webhook;

import com.example.billing.domain.shared.CustomerId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookEndpointTest {

    private static final CustomerId ALICE = CustomerId.of("alice");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void register_assignsRandomSecret() {
        var e1 = WebhookEndpoint.register(ALICE, "https://acme.example.com/hook", Set.of(), CLOCK);
        var e2 = WebhookEndpoint.register(ALICE, "https://acme.example.com/hook", Set.of(), CLOCK);
        assertThat(e1.secret()).hasSize(64);   // 32 bytes → 64 hex chars
        assertThat(e1.secret()).isNotEqualTo(e2.secret());
    }

    @Test
    void register_rejectsHttpExceptLocalhost() {
        assertThatThrownBy(() ->
                WebhookEndpoint.register(ALICE, "http://acme.example.com/hook", Set.of(), CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");

        // localhost 는 dev 용으로 허용
        var local = WebhookEndpoint.register(ALICE, "http://localhost:8080/hook", Set.of(), CLOCK);
        assertThat(local.url()).isEqualTo("http://localhost:8080/hook");
    }

    @Test
    void subscribesTo_emptySet_meansAllEvents() {
        var e = WebhookEndpoint.register(ALICE, "https://acme.example.com/hook", Set.of(), CLOCK);
        assertThat(e.subscribesTo("InvoiceIssued")).isTrue();
        assertThat(e.subscribesTo("WhateverEvent")).isTrue();
    }

    @Test
    void subscribesTo_explicitSet_onlySubscribed() {
        var e = WebhookEndpoint.register(ALICE, "https://acme.example.com/hook",
                Set.of("InvoiceIssued", "PaymentSucceeded"), CLOCK);
        assertThat(e.subscribesTo("InvoiceIssued")).isTrue();
        assertThat(e.subscribesTo("RefundProcessed")).isFalse();
    }

    @Test
    void rotateSecret_changesSecret() {
        var e = WebhookEndpoint.register(ALICE, "https://acme.example.com/hook", Set.of(), CLOCK);
        String before = e.secret();
        e.rotateSecret(CLOCK);
        assertThat(e.secret()).isNotEqualTo(before);
        assertThat(e.secret()).hasSize(64);
    }

    @Test
    void pauseResume_lifecycle() {
        var e = WebhookEndpoint.register(ALICE, "https://acme.example.com/hook", Set.of(), CLOCK);
        e.pause(CLOCK);
        assertThat(e.status()).isEqualTo(WebhookEndpointStatus.PAUSED);
        e.resume(CLOCK);
        assertThat(e.status()).isEqualTo(WebhookEndpointStatus.ACTIVE);
        // 두 번 pause 안 됨
        e.pause(CLOCK);
        assertThatThrownBy(() -> e.pause(CLOCK)).isInstanceOf(IllegalStateException.class);
    }
}
