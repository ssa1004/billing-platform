package com.example.billing.application.command

import com.example.billing.domain.shared.CustomerId

/**
 * 도메인 이벤트가 발생했을 때 — 그 이벤트를 구독한 customer endpoint 들에게 delivery 를 만든다.
 *
 * 호출자는 도메인 이벤트가 발생하는 곳 (예: invoice issue / payment 처리). 보통 outbox listener
 * 가 outbox 의 이벤트를 보면서 자동 호출. 여기서 직접 호출도 가능 (REST 진입점에서).
 *
 * [payload] 는 customer 에게 갈 JSON 본문 — 이미 직렬화된 상태.
 */
@JvmRecord
data class ScheduleWebhookCommand(
    val customerId: CustomerId,
    val eventType: String,
    val payload: String,
)
