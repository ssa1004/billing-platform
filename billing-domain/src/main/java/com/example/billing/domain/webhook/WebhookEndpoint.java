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
 * <p><b>전체 그림</b>: 외부 PG 사 (결제 게이트웨이) 가 가맹점에게 결제 결과를 push 통보하는
 * 그 매커니즘의 우리 버전. 여기선 *우리가 발신자, customer 가 수신자* 입니다. customer 가
 * 자기 서버 URL 을 등록해두면 invoice / payment / refund 같은 도메인 이벤트가 발생할 때 그
 * URL 로 HTTP POST 가 갑니다.
 *
 * <p><b>secret 이 endpoint 단위로 있는 이유 (HMAC 서명 검증)</b>: customer 서버가 "이 요청이
 * 진짜 우리 billing 시스템에서 온 게 맞나" 를 검증하기 위해 필요. 동작은:
 * <ol>
 *   <li>등록 시 우리가 256-bit 무작위 secret 을 생성 — 응답에 *한 번만 평문으로* 노출.
 *       customer 는 자기 서버에 그 secret 을 보관.</li>
 *   <li>매 webhook 발송 시 우리는 (body + timestamp) 를 secret 으로 HMAC (Hash-based Message
 *       Authentication Code, 비밀 키와 메시지로 만든 위조 방지 서명) 서명해 헤더에 실음.</li>
 *   <li>customer 는 같은 secret 으로 다시 계산해 헤더 값과 일치하면 진짜, 아니면 거절. URL 만
 *       알고 있는 공격자 / 중간자가 보낸 가짜 webhook 은 secret 이 없어 같은 값을 만들지 못함.</li>
 * </ol>
 * 분실 / 노출 시 {@link #rotateSecret} 으로 새 값 발급 (이전 secret 즉시 무효).
 *
 * <p><b>subscribedEventTypes 의 default 가 "모든 이벤트"</b>: customer 가 특정 타입만 받고
 * 싶을 때 (예: refund 알림만) 명시. 비어 있으면 *모든 이벤트 구독* 으로 간주 — "기본은 다
 * 받음, 관심 없는 것만 명시적으로 제외" 가 default 여서 온보딩 마찰이 적음.
 *
 * <p><b>도메인 invariant</b>:
 * <ul>
 *   <li>URL 은 https 만 허용 (production). 평문 http 로 secret 토큰을 헤더에 실어 보내면
 *       중간자 공격 (man-in-the-middle) 에 secret 이 그대로 노출됨. localhost 만 dev 편의로
 *       예외.</li>
 *   <li>secret 은 32바이트 (256bit) 무작위 값 — HMAC-SHA256 의 권장 키 길이. SecureRandom 사용.</li>
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
     * 자기 검증 로직에 즉시 반영해야 합니다. 잠시 양쪽을 모두 받는 유예 기간 (grace period)
     * 이 필요하면 별도 메서드 ({@code rotateSecretWithGrace}) 도입을 검토.
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
        // 호스트 경계까지 정확히 매칭 — "http://localhost.evil.com" 같은 prefix 우회 방어.
        if (url.startsWith("https://")) return;
        if (isLocalhostHttp(url)) return;
        throw new IllegalArgumentException(
                "url must be https (or http://localhost for dev): " + url);
    }

    private static boolean isLocalhostHttp(String url) {
        // host 부분만 추출해서 정확히 일치 여부를 확인.
        if (!url.startsWith("http://")) return false;
        String afterScheme = url.substring("http://".length());
        // 호스트 종료 문자: ":" (port), "/" (path), "?" (query), "#" (fragment), 끝.
        int hostEnd = afterScheme.length();
        for (int i = 0; i < afterScheme.length(); i++) {
            char c = afterScheme.charAt(i);
            if (c == ':' || c == '/' || c == '?' || c == '#') {
                hostEnd = i;
                break;
            }
        }
        String host = afterScheme.substring(0, hostEnd);
        return "localhost".equals(host) || "127.0.0.1".equals(host);
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
