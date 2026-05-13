package com.example.billing.domain.webhook

/**
 * Endpoint 활성 상태.
 *
 * - [ACTIVE] — 새 이벤트가 발생하면 이 endpoint 로 delivery 가 생성된다.
 * - [PAUSED] — 운영자/customer 가 잠시 끔. 새 delivery 가 생성 자체 안 됨. 이미 큐에 들어가
 *   있는 delivery 는 그대로 처리 (혹은 별도 정책으로 같이 멈출 수도).
 *
 * 완전 삭제는 row 삭제로. audit 가 필요해지면 별도 status 추가.
 */
enum class WebhookEndpointStatus {
    ACTIVE,
    PAUSED,
}
