package com.example.billing.application.port.`in`

import com.example.billing.application.command.ScheduleWebhookCommand

/**
 * 도메인 이벤트 → customer endpoint 들의 delivery 생성.
 *
 * `customer A` 가 InvoiceIssued 를 구독하는 endpoint 2개를 가지고 있다면:
 * 한 번의 호출이 2개 delivery 를 만든다 (각 endpoint 마다 1개씩). 각 delivery 는 자체 retry
 * 라이프사이클을 가짐 → 한 endpoint 가 다운돼도 다른 endpoint 로의 알림은 영향 없음.
 *
 * @return 생성된 delivery 개수 (= subscribed ACTIVE endpoint 수)
 */
interface ScheduleWebhookUseCase {
    fun schedule(command: ScheduleWebhookCommand): Int
}
