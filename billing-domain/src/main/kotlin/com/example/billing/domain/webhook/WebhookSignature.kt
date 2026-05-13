package com.example.billing.domain.webhook

import java.nio.charset.StandardCharsets
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Webhook 본문 HMAC-SHA256 서명 — customer 가 진위 검증할 수 있게.
 *
 * **왜 timestamp 를 같이 묶나**: 공격자가 과거 webhook 본문을 그대로 다시 보내는 replay 공격
 * 방지. customer 는 timestamp 가 너무 오래된 (예: 5분 이상) 요청은 거절하면 됨.
 *
 * **서명 알고리즘**: `HMAC-SHA256(secret, "{timestamp}.{body}")` — webhook 발신 SaaS 가 보편적
 * 으로 채택한 형식. customer side 검증 코드도 짧게 끝남.
 *
 * **헤더 형식**: `X-Webhook-Signature: sha256=<hex>`. 알고리즘 prefix 를 두는 건 나중에
 * SHA-512 / Ed25519 같은 걸로 갈아탈 때 backward-compatible 하게 운영하기 위함 (signature
 * scheme + timestamp 를 같은 헤더에 묶는 일반 관례를 따름).
 *
 * Kotlin `object` 로 — Java 에서는 `WebhookSignature.sign(...)` static 호출 그대로 유지
 * (`@JvmStatic`).
 */
object WebhookSignature {

    private const val ALGO = "HmacSHA256"
    private const val HEADER_PREFIX = "sha256="

    /**
     * @param secretHex endpoint 의 secret (hex 문자열). 그대로 byte[] 로 디코딩되어 키로 사용.
     * @param timestampSeconds delivery 시각 (epoch seconds). 헤더에도 따로 실음 → customer 가
     *        검증 시 같은 값 사용.
     * @param body HTTP body (JSON 직렬화된 payload). 한 byte 라도 다르면 서명 다름 → 변조 즉시 탐지.
     */
    @JvmStatic
    fun sign(secretHex: String, timestampSeconds: Long, body: String): String {
        val keyBytes = HexFormat.of().parseHex(secretHex)
        val dataBytes = "$timestampSeconds.$body".toByteArray(StandardCharsets.UTF_8)
        try {
            val mac = Mac.getInstance(ALGO)
            mac.init(SecretKeySpec(keyBytes, ALGO))
            val hmac = mac.doFinal(dataBytes)
            return HEADER_PREFIX + HexFormat.of().formatHex(hmac)
        } catch (e: NoSuchAlgorithmException) {
            // JVM 이 HmacSHA256 을 못 찾으면 시스템 자체가 깨진 상황 — 던지는 게 맞음
            throw IllegalStateException("HMAC unavailable: ${e.message}", e)
        } catch (e: InvalidKeyException) {
            throw IllegalStateException("HMAC unavailable: ${e.message}", e)
        }
    }
}
