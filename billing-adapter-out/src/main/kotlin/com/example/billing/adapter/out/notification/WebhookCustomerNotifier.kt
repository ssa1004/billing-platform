package com.example.billing.adapter.out.notification

import com.example.billing.application.port.out.CustomerNotifier
import com.example.billing.application.port.out.CustomerNotifier.NotificationType
import com.example.billing.domain.shared.CustomerId
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Clock

/**
 * Webhook 기반 알림 — customer 가 등록한 callback URL 로 HTTP POST 발송.
 *
 * Resilience4j 로 두 단계 보호:
 *  - Retry — 일시적 실패 (5xx / timeout) 시 짧게 재시도.
 *  - Circuit Breaker — 실패가 일정 비율 이상 누적되면 호출 자체를 잠시 차단해 다운된
 *    customer 서버를 우리가 더 이상 두드리지 않게 (DDoS 회피) + 우리 thread / connection
 *    자원을 보호.
 * 두 단계로도 실패한 알림은 DLQ (Dead Letter Queue, 처리 실패 메시지를 모아두는 큐) 같은 별도
 * 저장소에 기록해야 하지만 본 구현에서는 생략 (영속 통로는 ADR-0022 의 WebhookDelivery 가
 * 담당).
 *
 * 운영에서는 customer 별 webhook URL 매핑이 별도 store (CustomerProfile 등) 에 있어야
 * 합니다. 본 구현은 단순화를 위해 모든 customer 에게 같은 URL 로 보내는 stub.
 *
 * `open` — Resilience4j AOP 가 메서드를 가로채려면 open 이 필요. plugin.spring 이 자동 처리.
 */
@Component
@Profile("prod")
@ConfigurationProperties(prefix = "billing.notification.webhook")
open class WebhookCustomerNotifier(
    builder: RestClient.Builder,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : CustomerNotifier {

    private val client: RestClient = builder.build()
    var defaultUrl: String? = null

    @Retry(name = "customer-notifier")
    @CircuitBreaker(name = "customer-notifier")
    override fun notify(
        customerId: CustomerId,
        type: NotificationType,
        context: Map<String, Any>,
    ) {
        val body = HashMap<String, Any>()
        body["customerId"] = customerId.value
        body["type"] = type.name
        body["occurredAt"] = clock.instant().toString()
        body["context"] = context

        try {
            val json = objectMapper.writeValueAsString(body)
            client.post()
                .uri(defaultUrl ?: "")
                .header("Content-Type", "application/json")
                .header("X-Billing-Notification-Type", type.name)
                .body(json)
                .retrieve()
                .toBodilessEntity()
        } catch (e: JsonProcessingException) {
            log.warn("notification serialization failed for {} {}", customerId, type, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(WebhookCustomerNotifier::class.java)
    }
}
