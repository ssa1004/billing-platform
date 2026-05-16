package com.example.billing.application.port.`in`

import com.example.billing.domain.webhook.WebhookEndpointId

/**
 * Endpoint 활성/비활성/secret 갱신.
 *
 * [rotateSecret] 의 반환값은 새 secret (호출자가 응답에 한 번만 노출). 이전 secret 은
 * 즉시 무효화 — customer 가 grace period 가 필요한 경우는 별도 메서드 도입 검토.
 */
interface WebhookEndpointLifecycleUseCase {

    fun pause(endpointId: WebhookEndpointId)

    fun resume(endpointId: WebhookEndpointId)

    /** @return 새로 발급된 평문 secret (이 호출 1회만 노출). */
    fun rotateSecret(endpointId: WebhookEndpointId): String
}
