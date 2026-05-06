package com.example.billing.domain.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.util.HexFormat;

/**
 * Webhook 본문 HMAC-SHA256 서명 — customer 가 진위 검증할 수 있게.
 *
 * <p><b>왜 timestamp 를 같이 묶나</b>: 공격자가 과거 webhook 본문을 그대로 다시 보내는 *replay
 * 공격* 방지. customer 는 timestamp 가 너무 오래된 (예: 5분 이상) 요청은 거절하면 됨.
 *
 * <p><b>서명 알고리즘</b>: {@code HMAC-SHA256(secret, "{timestamp}.{body}")}
 * Stripe / GitHub / Slack 모두 같은 식. customer side 검증 코드도 짧게 끝남.
 *
 * <p><b>헤더 형식</b>: {@code X-Webhook-Signature: sha256=<hex>}<br>
 * 알고리즘 prefix 를 두는 건 나중에 SHA-512 / Ed25519 같은 걸로 갈아탈 때 backward-compatible
 * 하게 운영하기 위함 (Stripe v1=, t= 형식과 비슷한 의도).
 */
public final class WebhookSignature {

    private static final String ALGO = "HmacSHA256";
    private static final String HEADER_PREFIX = "sha256=";

    private WebhookSignature() {}

    /**
     * @param secretHex endpoint 의 secret (hex 문자열). 그대로 byte[] 로 디코딩되어 키로 사용.
     * @param timestampSeconds delivery 시각 (epoch seconds). 헤더에도 따로 실음 → customer 가 검증 시 같은 값 사용.
     * @param body HTTP body (JSON 직렬화된 payload). 한 byte 라도 다르면 서명 다름 → 변조 즉시 탐지.
     */
    public static String sign(String secretHex, long timestampSeconds, String body) {
        byte[] keyBytes = HexFormat.of().parseHex(secretHex);
        byte[] dataBytes = (timestampSeconds + "." + body).getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(keyBytes, ALGO));
            byte[] hmac = mac.doFinal(dataBytes);
            return HEADER_PREFIX + HexFormat.of().formatHex(hmac);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // JVM 이 HmacSHA256 을 못 찾으면 시스템 자체가 깨진 상황 — 던지는 게 맞음
            throw new IllegalStateException("HMAC unavailable: " + e.getMessage(), e);
        }
    }
}
