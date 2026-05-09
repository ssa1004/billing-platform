package com.example.billing.adapter.web.filter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * {@link RequestBodyFingerprint} 단위 테스트.
 *
 * 검증:
 *  - 같은 input → 같은 fingerprint (deterministic).
 *  - 1 byte 라도 다르면 → 다른 fingerprint.
 *  - 결과는 32 hex chars (16 byte = 128 bit prefix).
 *  - timing-safe matches: 다른 길이 / 다른 값 false, 같은 값 true.
 */
class RequestBodyFingerprintTest {

    @Test
    fun `compute is deterministic — same input produces same fingerprint`() {
        val body = """{"orderId":"o-1","amount":1000}""".toByteArray()

        val a = RequestBodyFingerprint.compute(body)
        val b = RequestBodyFingerprint.compute(body)

        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `compute output is 32 hex chars (16-byte prefix)`() {
        val fp = RequestBodyFingerprint.compute("anything".toByteArray())
        assertThat(fp).hasSize(32)
        assertThat(fp).matches("^[0-9a-f]{32}$")
    }

    @Test
    fun `single byte difference produces different fingerprint`() {
        val a = RequestBodyFingerprint.compute("""{"amount":1000}""".toByteArray())
        val b = RequestBodyFingerprint.compute("""{"amount":1001}""".toByteArray())

        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `whitespace difference produces different fingerprint`() {
        // JSON 의 공백도 raw byte 비교 — 같은 의미 다른 byte 면 다른 fingerprint.
        // 이건 의도된 동작: client 가 같은 키로 *완전히 동일한 byte 의 body* 를 보내야 같은 처리.
        // 의미적 동등성 (semantic equality) 까지 검증하려면 body 정규화가 필요한데, 결제 API
        // 표준도 raw byte 비교만 함.
        val a = RequestBodyFingerprint.compute("""{"a":1}""".toByteArray())
        val b = RequestBodyFingerprint.compute("""{"a": 1}""".toByteArray())

        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `null and empty body produce same fingerprint`() {
        val a = RequestBodyFingerprint.compute(null)
        val b = RequestBodyFingerprint.compute(ByteArray(0))

        assertThat(a).isEqualTo(b)
        assertThat(a).hasSize(32)
    }

    @Test
    fun `matches — equal strings return true`() {
        val fp = RequestBodyFingerprint.compute("body".toByteArray())
        assertThat(RequestBodyFingerprint.matches(fp, fp)).isTrue()
    }

    @Test
    fun `matches — different strings return false`() {
        val a = RequestBodyFingerprint.compute("body-a".toByteArray())
        val b = RequestBodyFingerprint.compute("body-b".toByteArray())

        assertThat(RequestBodyFingerprint.matches(a, b)).isFalse()
    }

    @Test
    fun `matches — different lengths return false (no exception)`() {
        // timing-safe 비교 명세상 길이 다른 입력에도 안전하게 false.
        assertThat(RequestBodyFingerprint.matches("short", "longer-string")).isFalse()
    }
}
