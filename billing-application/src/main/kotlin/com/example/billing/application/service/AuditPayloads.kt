package com.example.billing.application.service

/**
 * audit entry 의 before/after JSON 페이로드를 만드는 작은 빌더.
 *
 * **왜 이게 필요한가**: audit 의 before/after 는 도메인마다 컬럼 구조가 달라 일반
 * (generic) JSON 으로 담는다 ([com.example.billing.domain.audit.AuditEntry] javadoc 참조).
 * 각 service 가 `String.format("{\"k\":\"%s\"}", v)` 로 직접 조립하던 것을 모아둔다.
 *
 * 직접 조립의 문제는 값 escape 누락이다. 페이로드에 들어가는 값 중 `customerId`
 * (외부 CRM 키), PG `errorMessage` / `errorCode`, `idempotencyKey` 등은
 * 자유 문자열이라 큰따옴표 · 역슬래시 · 제어문자가 섞일 수 있다. escape 없이 끼워넣으면
 * 생성된 JSON 이 깨져 — audit 의 존재 이유인 forensic / 회계 감사 단계에서 페이로드를
 * 파싱하지 못한다. PG 가 돌려준 `errorMessage` 에 `"` 한 글자만 있어도 그렇다.
 *
 * 운영 표준은 Jackson + 도메인별 DTO 지만, before/after 는 디버깅 / forensic 용이라
 * 핵심 식별자 + 상태 + 금액 정도만 평면(flat) 으로 담으면 충분하다 — 그 좁은 용도에 맞춘
 * 최소 빌더다. 중첩 객체 · 배열은 의도적으로 지원하지 않는다.
 *
 * 사용 예:
 * ```
 * val json = AuditPayloads.`object`()
 *     .put("customerId", customerId.value)
 *     .put("amount", invoice.total.amount)   // null 아닌 Object 는 toString()
 *     .build()
 * ```
 */
internal object AuditPayloads {

    @JvmStatic
    fun `object`(): Builder = Builder()

    internal class Builder {

        // "key":"value" 조각을 콤마로 잇는다. 키 순서 = put 호출 순서.
        private val body = StringBuilder("{")
        private var first = true

        /**
         * 문자열 필드 추가. [value] 가 null 이면 JSON `null` 리터럴 (따옴표 없음),
         * 아니면 escape 한 문자열 리터럴.
         */
        fun put(key: String, value: String?): Builder {
            if (!first) body.append(',') else first = false
            body.append(quote(key)).append(':').append(if (value == null) "null" else quote(value))
            return this
        }

        /**
         * 임의 객체 필드 추가 — `toString()` 결과를 문자열로 담는다. 금액(BigDecimal),
         * enum, `YearMonth` 등 toString 이 곧 표현인 값에 쓴다. null 이면 JSON `null`.
         */
        fun put(key: String, value: Any?): Builder = put(key, value?.toString())

        fun build(): String = body.toString() + "}"
    }

    // JSON 문자열 리터럴 escape (RFC 8259 §7). 의존성 없이 최소 구현 — 빌딩 블록이
    // 단순 평면 객체뿐이라 이 정도면 충분하다.
    private fun quote(raw: String): String {
        val sb = StringBuilder(raw.length + 2)
        sb.append('"')
        for (c in raw) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                FF_CHAR -> sb.append("\\f")
                else -> {
                    if (c.code < 0x20) {
                        // 그 외 제어문자는 \uXXXX 로.
                        sb.append("\\u%04x".format(c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private const val FF_CHAR = '\u000C'
}
