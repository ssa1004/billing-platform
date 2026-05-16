package com.example.billing.application.service

import com.example.billing.application.exception.WebhookEndpointNotFoundException
import com.example.billing.application.port.`in`.WebhookEndpointLifecycleUseCase
import com.example.billing.application.port.out.WebhookEndpointRepository
import com.example.billing.domain.webhook.WebhookEndpointId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
open class WebhookEndpointLifecycleService(
    private val endpoints: WebhookEndpointRepository,
    private val clock: Clock,
) : WebhookEndpointLifecycleUseCase {

    @Transactional
    override fun pause(endpointId: WebhookEndpointId) {
        val endpoint = endpoints.findById(endpointId)
            .orElseThrow { WebhookEndpointNotFoundException(endpointId) }
        endpoint.pause(clock)
        endpoints.save(endpoint)
        log.info("webhook endpoint paused id={}", endpointId)
    }

    @Transactional
    override fun resume(endpointId: WebhookEndpointId) {
        val endpoint = endpoints.findById(endpointId)
            .orElseThrow { WebhookEndpointNotFoundException(endpointId) }
        endpoint.resume(clock)
        endpoints.save(endpoint)
        log.info("webhook endpoint resumed id={}", endpointId)
    }

    @Transactional
    override fun rotateSecret(endpointId: WebhookEndpointId): String {
        val endpoint = endpoints.findById(endpointId)
            .orElseThrow { WebhookEndpointNotFoundException(endpointId) }
        endpoint.rotateSecret(clock)
        endpoints.save(endpoint)
        log.info("webhook endpoint secret rotated id={}", endpointId)
        return endpoint.secret
    }

    companion object {
        private val log = LoggerFactory.getLogger(WebhookEndpointLifecycleService::class.java)
    }
}
