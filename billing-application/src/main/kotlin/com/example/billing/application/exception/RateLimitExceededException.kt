package com.example.billing.application.exception

/**
 * admin endpoint 의 호출자 IP × scope rate limit 초과. controller 에서 429 + Retry-After 헤더로
 * 매핑된다 ([com.example.billing.adapter.web.exception.GlobalExceptionHandler]).
 *
 * @property scope `admin:dlq:read` / `admin:dlq:write` / `admin:dlq:bulk` 등 — 같은 IP 라도
 *   작업군 별로 별도 bucket.
 * @property retryAfterMillis 다음 시도까지 권장 대기 (ms). 0 이면 즉시 다시 시도 가능 (현재 미사용).
 */
class RateLimitExceededException(
    val scope: String,
    val retryAfterMillis: Long,
) : RuntimeException("rate limit exceeded for scope=$scope retryAfter=${retryAfterMillis}ms")
