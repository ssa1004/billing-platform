package com.example.billing.application.service

import com.example.billing.application.command.RegisterWebhookEndpointCommand
import com.example.billing.application.port.`in`.RegisterWebhookEndpointUseCase
import com.example.billing.application.port.out.WebhookEndpointRepository
import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.webhook.WebhookEndpoint
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Customer 가 webhook 수신 endpoint 등록.
 *
 * secret 은 도메인이 자동 생성 (호출자가 직접 정하지 않음). 응답 한 번만 평문 노출 →
 * REST 컨트롤러가 그 응답에서 secret 을 customer 에게 표시 → 이후 모든 조회에서는 hash 만.
 */
@Service
open class RegisterWebhookEndpointService(
    private val endpoints: WebhookEndpointRepository,
    private val idempotency: IdempotentExecution,
    private val clock: Clock,
) : RegisterWebhookEndpointUseCase {

    @Transactional
    override fun register(command: RegisterWebhookEndpointCommand): WebhookEndpoint {
        idempotency.acquireAndReleaseOnRollback(command.idempotencyKey)

        val endpoint = WebhookEndpoint.register(
            CustomerId.of(command.customerId),
            command.url,
            command.subscribedEventTypes,
            clock,
        )
        endpoints.save(endpoint)
        val subscribedTypes = endpoint.subscribedEventTypes()
        log.info(
            "webhook endpoint registered id={} customer={} url={} events={}",
            endpoint.id, endpoint.customerId, endpoint.url,
            if (subscribedTypes.isEmpty()) "ALL" else subscribedTypes,
        )
        return endpoint
    }

    companion object {
        private val log = LoggerFactory.getLogger(RegisterWebhookEndpointService::class.java)
    }
}
