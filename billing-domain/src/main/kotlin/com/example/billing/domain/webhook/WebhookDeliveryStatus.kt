package com.example.billing.domain.webhook

/**
 * Webhook delivery 시도 상태.
 *
 * ```
 *   PENDING ─────beginAttempt──▶ IN_FLIGHT ─┬─markSuccess─▶ SUCCESS
 *      ▲                                    │
 *      │                                    ├─markRetryable (남은 시도 있음) ─▶ PENDING
 *      └────────────────────────────────────┤
 *                                           └─markRetryable (시도 다 씀) ─▶ DEAD_LETTERED
 *                                           │
 *                                           └─markDead (4xx 같은 영구 실패)──▶ DEAD_LETTERED
 *
 *   DEAD_LETTERED ──replay()──▶ PENDING        (운영자 수동 재시도)
 * ```
 *
 * **왜 4가지로 나뉘나**:
 * - [PENDING]: 큐에 들어가 worker 가 픽업 대기. `FOR UPDATE SKIP LOCKED` 로 잡음.
 * - [IN_FLIGHT]: worker 가 픽업해 HTTP POST 진행 중. 워커가 죽어도 복구되도록 timeout 설정 필요.
 * - [SUCCESS]: 2xx 응답 받음. 종착.
 * - [DEAD_LETTERED]: 재시도 다 썼거나 영구 실패 (4xx 등). 운영자 화면에서 보고 수동 replay 가능.
 */
enum class WebhookDeliveryStatus {
    PENDING,
    IN_FLIGHT,
    SUCCESS,
    DEAD_LETTERED,
}
