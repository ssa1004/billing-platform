package com.example.billing.application.service

import com.example.billing.application.exception.WebhookDeliveryNotFoundException
import com.example.billing.application.port.`in`.ReplayWebhookDeliveryUseCase
import com.example.billing.application.port.out.WebhookDeliveryRepository
import com.example.billing.domain.webhook.WebhookDeliveryId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
open class ReplayWebhookDeliveryService(
    private val deliveries: WebhookDeliveryRepository,
    private val clock: Clock,
) : ReplayWebhookDeliveryUseCase {

    @Transactional
    override fun replay(deliveryId: WebhookDeliveryId) {
        val delivery = deliveries.findById(deliveryId)
            .orElseThrow { WebhookDeliveryNotFoundException(deliveryId) }
        delivery.replay(clock)
        deliveries.save(delivery)
        log.info("webhook delivery replayed id={} attemptsSoFar={}", deliveryId, delivery.attemptCount)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ReplayWebhookDeliveryService::class.java)
    }
}
