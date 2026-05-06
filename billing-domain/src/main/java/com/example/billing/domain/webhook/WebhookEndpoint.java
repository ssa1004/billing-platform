package com.example.billing.domain.webhook;

import com.example.billing.domain.shared.CustomerId;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Customer 가 등록한 webhook 수신 endpoint.
 *
 * <p><b>실제 모습</b>: PG (Toss / Stripe / PayPal) 가 가맹점에게 결제 이벤트를 보내는 그
 * 매커니즘과 똑같다. 우리는 *발신자 (PG 입장)*. customer 가 자기 서버 URL 을 등록해두면
 * invoice / payment / refund 같은 도메인 이벤트가 발생할 때 그 URL 로 HTTP POST 가 간다.
 *
 * <p><b>왜 secret 이 endpoint 단위로 있나</b>: customer 서버가 "이 요청이 진짜 우리
 * billing 시스템이 보낸 게 맞는지" 검증하기 위해. 우리는 매 delivery 의 body + timestamp 를
 * 이 secret 으로 HMAC 서명해 헤더에 실어 보낸다. customer 는 같은 secret 으로 다시 계산해
 * 일치 여부 확인 → 일치하면 진짜, 아니면 가짜 (URL 만 알고 있는 공격자 / 중간자 변조 등).
 * 그래서 secret 은 *endpoint 등록 시점에 1번만 평문으로 응답에 포함*, 그 뒤로는 hash 만 보관.
 * 분실 시 {@link #rotateSecret} 으로 갱신.
 *
 * <p><b>subscribedEventTypes</b>: customer 가 모든 이벤트가 아니라 특정 타입만 받고 싶을 수
 * 있음 (예: refund 만 알림 받기). 비어 있으면 *모든 이벤트* 로 간주 — "관심 없으면 명시 안 함"
 * 이 default 라 onboarding 마찰 줄임.
 *
 * <p><b>도메인 invariant</b>:
 * <ul>
 *   <li>URL 은 https 만 허용 (production). 평문 http 로 secret 토큰을 헤더에 실어 보내면
 *       중간자 공격에 노출.</li>
 *   <li>secret 은 32바이트 (256bit) 이상 — HMAC-SHA256 의 권장 키 길이.</li>
 * </ul>
 */
public final class WebhookEndpoint {

    /** secret 길이 (bytes). 256-bit HMAC key. */
    private static final int SECRET_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();

    private final WebhookEndpointId id;
    private final CustomerId customerId;
    private final String url;
    /** Hex 인코딩된 256-bit 키. customer 응답에는 한 번만 평문 노출. */
    private String secret;
    /** 빈 set = 모든 이벤트 구독 (default). */
    private final Set<String> subscribedEventTypes;
    private WebhookEndpointStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private WebhookEndpoint(WebhookEndpointId id, CustomerId customerId, String url,
                            String secret, Set<String> subscribedEventTypes,
                            WebhookEndpointStatus status,
                            Instant createdAt, Instant updatedAt, long version) {
        this.id = id;
        this.customerId = customerId;
        this.url = url;
        this.secret = secret;
        this.subscribedEventTypes = new LinkedHashSet<>(subscribedEventTypes);
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    /**
     * 새 endpoint 등록. secret 은 자동 생성 (호출자가 직접 정하지 않음 — 항상 안전한 난수 사용).
     */
    public static WebhookEndpoint register(CustomerId customerId, String url,
                                           Set<String> subscribedEventTypes, Clock clock) {
        Objects.requireNonNull(customerId);
        Objects.requireNonNull(url);
        Objects.requireNonNull(subscribedEventTypes);
        validateUrl(url);
        Instant now = clock.instant();
        return new WebhookEndpoint(
                WebhookEndpointId.newId(), customerId, url, generateSecret(),
                subscribedEventTypes, WebhookEndpointStatus.ACTIVE, now, now, 0L);
    }

    public static WebhookEndpoint restore(WebhookEndpointId id, CustomerId customerId, String url,
                                          String secret, Set<String> subscribedEventTypes,
                                          WebhookEndpointStatus status,
                                          Instant createdAt, Instant updatedAt, long version) {
        return new WebhookEndpoint(id, customerId, url, secret, subscribedEventTypes,
                status, createdAt, updatedAt, version);
    }

    /**
     * 이 endpoint 가 주어진 이벤트 타입을 구독했는지.
     * 빈 subscribedEventTypes = 모든 이벤트 구독 (default).
     */
    public boolean subscribesTo(String eventType) {
        if (subscribedEventTypes.isEmpty()) return true;
        return subscribedEventTypes.contains(eventType);
    }

    public void pause(Clock clock) {
        if (status != WebhookEndpointStatus.ACTIVE) {
            throw new IllegalStateException("only ACTIVE can be paused: status=" + status);
        }
        this.status = WebhookEndpointStatus.PAUSED;
        this.updatedAt = clock.instant();
    }

    public void resume(Clock clock) {
        if (status != WebhookEndpointStatus.PAUSED) {
            throw new IllegalStateException("only PAUSED can be resumed: status=" + status);
        }
        this.status = WebhookEndpointStatus.ACTIVE;
        this.updatedAt = clock.instant();
    }

    /**
     * Secret 을 새로 발급. 이전 secret 은 즉시 무효 — customer 는 응답으로 받은 새 secret 을
     * 자기 검증 로직에 즉시 반영해야 한다. 잠시 양쪽을 모두 받는 grace period 가 필요하면
     * 별도 메서드 ({@code rotateSecretWithGrace}) 도입 검토.
     */
    public void rotateSecret(Clock clock) {
        this.secret = generateSecret();
        this.updatedAt = clock.instant();
    }

    private static void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        // production 은 https 강제. 로컬 / 테스트 (http://localhost) 만 예외.
        boolean https = url.startsWith("https://");
        boolean localhost = url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1");
        if (!https && !localhost) {
            throw new IllegalArgumentException(
                    "url must be https (or http://localhost for dev): " + url);
        }
    }

    private static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RNG.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    // Getters
    public WebhookEndpointId id() { return id; }
    public CustomerId customerId() { return customerId; }
    public String url() { return url; }
    public String secret() { return secret; }
    public Set<String> subscribedEventTypes() { return Set.copyOf(subscribedEventTypes); }
    public WebhookEndpointStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
