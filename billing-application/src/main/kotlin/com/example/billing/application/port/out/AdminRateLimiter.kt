package com.example.billing.application.port.out

import com.example.billing.domain.shared.RateLimitDecision

/**
 * admin (운영) endpoint 의 호출자별 token bucket. 일반 customer-facing rate-limit 와 다른 점:
 * channel / recipient 개념이 없고 IP + path scope 기반. notification-hub ADR-0015 의 같은
 * port 와 형태 일치 — 공통 패턴 (ADR-0033).
 *
 * 한도 / 윈도우는 어댑터 단 config 가 정함 (default 분당 60회). [scope] 는 같은 IP 라도 다른
 * admin 작업 그룹은 별도 카운트 (예: read 와 bulk 를 분리).
 *
 * 기본 scope 값:
 * - `admin:dlq:read` — list / search / detail / stats / bulk-jobs 조회
 * - `admin:dlq:write` — 단건 replay / discard
 * - `admin:dlq:bulk` — bulk-replay / bulk-discard 호출 (가장 위험)
 */
interface AdminRateLimiter {

    fun tryConsume(scope: String, callerKey: String): RateLimitDecision
}
