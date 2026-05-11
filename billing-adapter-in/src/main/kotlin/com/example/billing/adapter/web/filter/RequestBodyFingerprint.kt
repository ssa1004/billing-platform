package com.example.billing.adapter.web.filter

import java.security.MessageDigest
import java.util.HexFormat

/**
 * 요청 본문의 SHA-256 fingerprint — Idempotency-Key 는 같은데 본문이 다른 사고 검출에 사용.
 *
 * <p><b>왜 SHA-256 prefix 16 byte 인가</b>:</p>
 * <ul>
 *   <li>SHA-256 자체는 해시 충돌 확률이 사실상 0 (2^-128).</li>
 *   <li>16 byte (128 bit) prefix → 비교 비용 / 저장 비용 절약. 충돌 확률 ≈ 2^-64 — 같은 customer
 *       의 24h 안의 모든 결제 요청을 모아도 충돌 발생 확률 무시 가능.</li>
 *   <li>full hash (32 byte) 는 Redis 저장 비용 2배 + 비교 시 cache miss 가 더 큼.</li>
 * </ul>
 *
 * <p><b>왜 full body 비교가 아닌가</b>: body 가 1MB 인 요청 (드문 경우지만) 의 byte-by-byte 비교는
 * 대용량 메모리 + Redis SET / GET 비용 큼. SHA-256 prefix 는 상수 32 hex chars 라 비교 비용 일정.
 * 같은 fingerprint = 거의 같은 body (충돌 무시), 다른 fingerprint = 확실히 다른 body — 우리에게
 * 필요한 모든 동작.
 *
 * <p><b>timing-safe 비교 ({@link MessageDigest#isEqual})</b>: 우리 use-case 는 공격 이 아닌
 * client bug 검출 이라 timing attack 위험은 낮지만, 표준 보안 패턴 따르기 + 향후 secret 비교에
 * 동일 utility 재사용 여지를 고려해 timing-safe.
 *
 * <p>ADR-0028 참고.</p>
 */
internal object RequestBodyFingerprint {

    private const val ALGORITHM = "SHA-256"

    /** Hash prefix 길이 (bytes). 16 byte = 32 hex chars. */
    private const val PREFIX_BYTES = 16

    /**
     * @param body UTF-8 인코딩된 byte[] 본문. null 또는 빈 byte[] 는 빈 fingerprint 의 hash 가 됨
     *             (빈 본문도 동일성 비교 의미 있음 — POST 가 빈 body 인 경우).
     * @return 32 hex chars 의 fingerprint 문자열. 같은 입력은 항상 같은 출력.
     */
    fun compute(body: ByteArray?): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
        val hash = digest.digest(body ?: ByteArray(0))
        // 앞 16 byte 만 — 충돌 확률 ~2^-64 (24h 의 결제 요청 수에 비해 천문학적 여유).
        return HexFormat.of().formatHex(hash, 0, PREFIX_BYTES)
    }

    /**
     * Timing-safe 비교 — 두 fingerprint 의 길이가 다르면 false, 같으면 byte 단위 고정 시간 비교.
     * 표준 보안 패턴.
     */
    fun matches(a: String, b: String): Boolean {
        // String.equals 는 첫 mismatch 에서 즉시 false — timing attack 측정 가능.
        // MessageDigest.isEqual 는 전체 byte 비교 를 항상 끝까지 수행.
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
