package com.example.billing.application.port.out

import com.example.billing.domain.shared.CustomerId

/**
 * 고객 알림 — 청구서 발행 / 결제 성공 / 결제 실패 등 라이프사이클 이벤트를 customer 에게 통지.
 *
 * 채널은 customer 의 preference 에 따라 email / webhook / Slack 등 분기. 본 인터페이스는
 * channel-agnostic — 구현체가 선택.
 *
 * [NotificationType] 별로 template 적용. 다국어는 customer locale 에서 결정.
 *
 * 실패 시 동작: 재시도는 구현체 책임 (Resilience4j), 영구 실패는 dead letter 로 기록.
 * application service 는 결과 신경 쓰지 않음 (fire-and-forget).
 */
interface CustomerNotifier {

    fun notify(
        customerId: CustomerId,
        type: NotificationType,
        context: Map<String, @JvmSuppressWildcards Any>,
    )

    enum class NotificationType {
        INVOICE_ISSUED,
        PAYMENT_SUCCEEDED,
        PAYMENT_FAILED,
        INVOICE_OVERDUE,
        REFUND_PROCESSED,

        /** 예산 임계 초과 알림 — context: ruleId, threshold, projectedCost, overshootRatio */
        BUDGET_ALERT,
    }
}
