package com.example.billing.adapter.out.persistence.jpa

import com.example.billing.adapter.out.persistence.jpa.mapper.WebhookDeliveryJpaMapper
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataWebhookDeliveryRepository
import com.example.billing.application.port.out.WebhookDeliveryRepository
import com.example.billing.domain.webhook.WebhookDelivery
import com.example.billing.domain.webhook.WebhookDeliveryId
import com.example.billing.domain.webhook.WebhookDeliveryStatus
import com.example.billing.domain.webhook.WebhookEndpointId
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.Optional

@Repository
class JpaWebhookDeliveryRepositoryAdapter(
    private val jpa: SpringDataWebhookDeliveryRepository,
) : WebhookDeliveryRepository {

    override fun save(delivery: WebhookDelivery) {
        jpa.save(WebhookDeliveryJpaMapper.toEntity(delivery))
    }

    override fun findById(id: WebhookDeliveryId): Optional<WebhookDelivery> =
        jpa.findById(id.value).map(WebhookDeliveryJpaMapper::toDomain)

    override fun claimPending(now: Instant, limit: Int): List<WebhookDelivery> =
        jpa.claimPending(now, PageRequest.of(0, limit))
            .map(WebhookDeliveryJpaMapper::toDomain)

    override fun findByEndpoint(endpointId: WebhookEndpointId, limit: Int): List<WebhookDelivery> =
        jpa.findByEndpointIdOrderByCreatedAtDesc(endpointId.value, PageRequest.of(0, limit))
            .map(WebhookDeliveryJpaMapper::toDomain)

    override fun findByStatus(status: WebhookDeliveryStatus, limit: Int): List<WebhookDelivery> =
        jpa.findByStatusOrderByCreatedAtDesc(status, PageRequest.of(0, limit))
            .map(WebhookDeliveryJpaMapper::toDomain)
}
