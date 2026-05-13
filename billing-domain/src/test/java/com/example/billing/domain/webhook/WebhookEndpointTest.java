package com.example.billing.domain.webhook;

import com.example.billing.domain.shared.CustomerId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
    void register_rejectsLocalhostPrefixSpoofedHost() {
        // "http://localhost.evil.com" 은 startsWith("http://localhost") 만 보면 통과하지만
        // 실제 호스트는 evil.com — SSRF / spoofing 막기 위해 host 경계까지 정확히 매칭해야 함.
        assertThatThrownBy(() ->
                WebhookEndpoint.register(ALICE, "http://localhost.evil.com/hook", Set.of(), CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");

        assertThatThrownBy(() ->
                WebhookEndpoint.register(ALICE, "http://127.0.0.1.attacker.com/hook", Set.of(), CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");

        // 진짜 localhost 는 여전히 통과해야 함.
        assertThat(WebhookEndpoint.register(ALICE, "http://localhost/hook", Set.of(), CLOCK).url())
                .isEqualTo("http://localhost/hook");
        assertThat(WebhookEndpoint.register(ALICE, "http://127.0.0.1/hook", Set.of(), CLOCK).url())
                .isEqualTo("http://127.0.0.1/hook");
    }

    @Test
    void register_rejectsHttpsCloudMetadataAndPrivateRanges() {
        // OWASP API7 — Server-Side Request Forgery. customer 가 https 만 쓰면 우회 가능하던
        // private / metadata 대역. 도메인 검사가 host 단위로 차단.

        // AWS / GCP / Azure IMDS — link-local 169.254.169.254
        assertThatThrownBy(() ->
                WebhookEndpoint.register(ALICE, "https://169.254.169.254/latest/meta-data/", Set.of(), CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");

        // GCP metadata DNS — instance scoped metadata 서버
        assertThatThrownBy(() ->
                WebhookEndpoint.register(ALICE, "https://metadata.google.internal/computeMetadata/v1/", Set.of(), CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");

        // RFC 1918 — 10/8, 172.16/12, 192.168/16
        assertThatThrownBy(() ->
                WebhookEndpoint.register(ALICE, "https://10.0.0.5/hook", Set.of(), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                WebhookEndpoint.register(ALICE, "https://172.20.10.1/hook", Set.of(), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                WebhookEndpoint.register(ALICE, "https://192.168.1.5/hook", Set.of(), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);

        // loopback https — 자기 자신을 가리키는 https. http loopback 은 dev exception 으로 허용되지만
        // https loopback 은 정상 운영에 의미가 없음.
        assertThatThrownBy(() ->
                WebhookEndpoint.register(ALICE, "https://127.0.0.1/hook", Set.of(), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                WebhookEndpoint.register(ALICE, "https://localhost/hook", Set.of(), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);

        // IPv6 — loopback / unique-local / link-local
        assertThatThrownBy(() ->
                WebhookEndpoint.register(ALICE, "https://[::1]/hook", Set.of(), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                WebhookEndpoint.register(ALICE, "https://[fc00::1]/hook", Set.of(), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                WebhookEndpoint.register(ALICE, "https://[fe80::1]/hook", Set.of(), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);

        // 정상 public host 는 여전히 통과.
        var ok = WebhookEndpoint.register(ALICE, "https://acme.example.com/hook", Set.of(), CLOCK);
        assertThat(ok.url()).isEqualTo("https://acme.example.com/hook");
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
    void rotateSecret_changesSecret_andDemotesPreviousToGrace() {
        // ADR-0029: rotate 후 이전 secret 은 24h grace 동안 함께 유효.
        var e = WebhookEndpoint.register(ALICE, "https://acme.example.com/hook", Set.of(), CLOCK);
        String before = e.secret();
        e.rotateSecret(CLOCK);
        assertThat(e.secret()).isNotEqualTo(before);
        assertThat(e.secret()).hasSize(64);
        // 이전 secret 이 previousSecret 으로 demote 되었음.
        assertThat(e.previousSecret()).contains(before);
        assertThat(e.previousSecretValidUntil()).contains(CLOCK.instant().plus(Duration.ofHours(24)));
    }

    @Test
    void rotateSecret_register_withoutRotation_hasNoPreviousSecret() {
        var e = WebhookEndpoint.register(ALICE, "https://acme.example.com/hook", Set.of(), CLOCK);
        assertThat(e.previousSecret()).isEmpty();
        assertThat(e.previousSecretValidUntil()).isEmpty();
    }

    @Test
    void activeSecrets_includesPreviousWithinGrace_excludesAfterExpiry() {
        var e = WebhookEndpoint.register(ALICE, "https://acme.example.com/hook", Set.of(), CLOCK);
        String oldSecret = e.secret();
        e.rotateSecret(CLOCK);
        String newSecret = e.secret();

        // grace 안: 두 secret 모두 활성 (현재 secret 이 첫 번째, previous 가 두 번째).
        Clock midGrace = Clock.fixed(CLOCK.instant().plus(Duration.ofHours(1)), ZoneOffset.UTC);
        List<String> active = e.activeSecrets(midGrace);
        assertThat(active).containsExactly(newSecret, oldSecret);

        // grace 밖: 현재 secret 만.
        Clock pastGrace = Clock.fixed(CLOCK.instant().plus(Duration.ofHours(25)), ZoneOffset.UTC);
        assertThat(e.activeSecrets(pastGrace)).containsExactly(newSecret);
    }

    @Test
    void rotateSecret_twiceWithinGrace_overwritesPreviousChain() {
        // 짧은 사이에 두 번 rotate — 가운데 secret 은 사라짐 (previous 는 마지막 직전 secret 으로 덮어씀).
        // 3개 동시 활성은 운영 복잡도만 키우고 의미 없음. 보안적으로도 안전 (덮어씌워진 secret 도 무효).
        var e = WebhookEndpoint.register(ALICE, "https://acme.example.com/hook", Set.of(), CLOCK);
        e.rotateSecret(CLOCK);
        String middleSecret = e.secret();   // 첫 rotate 후 secret
        Clock laterClock = Clock.fixed(CLOCK.instant().plus(Duration.ofMinutes(10)), ZoneOffset.UTC);
        e.rotateSecret(laterClock);

        // previous 는 방금 직전 secret 이어야 함 — 첫 번째 secret 은 사라짐.
        assertThat(e.previousSecret()).contains(middleSecret);
    }

    @Test
    void rotateSecret_customGrace_negativeOrZeroRejected() {
        var e = WebhookEndpoint.register(ALICE, "https://acme.example.com/hook", Set.of(), CLOCK);

        assertThatThrownBy(() -> e.rotateSecret(CLOCK, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> e.rotateSecret(CLOCK, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expirePreviousSecretIfDue_clearsExpiredGrace() {
        var e = WebhookEndpoint.register(ALICE, "https://acme.example.com/hook", Set.of(), CLOCK);
        e.rotateSecret(CLOCK);
        assertThat(e.previousSecret()).isPresent();

        // grace 안 — cleanup 호출해도 변경 없음.
        Clock midGrace = Clock.fixed(CLOCK.instant().plus(Duration.ofHours(1)), ZoneOffset.UTC);
        assertThat(e.expirePreviousSecretIfDue(midGrace)).isFalse();
        assertThat(e.previousSecret()).isPresent();

        // grace 후 — 정리됨.
        Clock pastGrace = Clock.fixed(CLOCK.instant().plus(Duration.ofHours(25)), ZoneOffset.UTC);
        assertThat(e.expirePreviousSecretIfDue(pastGrace)).isTrue();
        assertThat(e.previousSecret()).isEmpty();
        assertThat(e.previousSecretValidUntil()).isEmpty();
    }

    @Test
    void expirePreviousSecretIfDue_noPrevious_isNoop() {
        var e = WebhookEndpoint.register(ALICE, "https://acme.example.com/hook", Set.of(), CLOCK);
        assertThat(e.expirePreviousSecretIfDue(CLOCK)).isFalse();
    }

    @Test
    void restore_inconsistentPreviousSecretFields_throws() {
        // invariant 검증: previousSecret / previousSecretValidUntil 은 짝.
        assertThatThrownBy(() -> WebhookEndpoint.restore(
                WebhookEndpointId.newId(), ALICE, "https://acme.example.com/hook",
                "current-secret",
                "leftover-previous", null,    // valid_until 만 빠짐 — 운영 사고 신호
                Set.of(),
                WebhookEndpointStatus.ACTIVE,
                CLOCK.instant(), CLOCK.instant(), 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("previousSecret");
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
