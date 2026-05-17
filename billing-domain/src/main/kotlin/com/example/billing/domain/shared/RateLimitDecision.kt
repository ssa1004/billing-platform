package com.example.billing.domain.shared

/**
 * Token bucket 결정 결과. admin endpoint 의 호출자 IP × scope 별로 분당 N개 허용 같은 정책에서
 * 사용. notification-hub 의 같은 이름 record (ADR-0015) 와 형태 통일 — 추후 공통 모듈로 빼낼
 * 여지.
 *
 * @property allowed 이번 호출이 허용되는지
 * @property remainingTokens 남은 토큰 수 (디버그 / 메트릭)
 * @property retryAfterMillis allowed=false 일 때 다음 시도까지 대기 권장 (ms)
 */
@JvmRecord
data class RateLimitDecision(
    val allowed: Boolean,
    val remainingTokens: Long,
    val retryAfterMillis: Long,
) {

    companion object {

        @JvmStatic
        fun allow(remaining: Long): RateLimitDecision = RateLimitDecision(true, remaining, 0)

        @JvmStatic
        fun deny(retryAfterMillis: Long): RateLimitDecision =
            RateLimitDecision(false, 0, retryAfterMillis)
    }
}
