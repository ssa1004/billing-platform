package com.example.billing.domain.webhook;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    /** 검증 — 다른 사람 (또는 customer) 가 같은 알고리즘으로 계산해서 일치하는지. */
    @Test
    void sign_matchesIndependentlyComputedHmac() throws Exception {
        String secret = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
        long ts = 1_700_000_000L;
        String body = "{\"event\":\"InvoiceIssued\",\"id\":\"inv-1\"}";

        String got = WebhookSignature.sign(secret, ts, body);

        // 직접 HMAC 계산
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HexFormat.of().parseHex(secret), "HmacSHA256"));
        byte[] expected = mac.doFinal((ts + "." + body).getBytes(StandardCharsets.UTF_8));
        String expectedHeader = "sha256=" + HexFormat.of().formatHex(expected);

        assertThat(got).isEqualTo(expectedHeader);
    }

    @Test
    void sign_bodyDifferenceProducesDifferentSignature() {
        String secret = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
        long ts = 1_700_000_000L;

        String a = WebhookSignature.sign(secret, ts, "{\"x\":1}");
        String b = WebhookSignature.sign(secret, ts, "{\"x\":2}");

        // 1바이트만 달라도 서명 완전히 다름
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void sign_secretDifferenceProducesDifferentSignature() {
        long ts = 1_700_000_000L;
        String body = "{}";

        String a = WebhookSignature.sign(
                "0000000000000000000000000000000000000000000000000000000000000000", ts, body);
        String b = WebhookSignature.sign(
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", ts, body);

        assertThat(a).isNotEqualTo(b);
    }
}
