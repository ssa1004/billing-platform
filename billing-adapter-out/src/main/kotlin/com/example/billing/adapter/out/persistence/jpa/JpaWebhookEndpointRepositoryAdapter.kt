package com.example.billing.adapter.out.persistence.jpa

import com.example.billing.adapter.out.persistence.jpa.mapper.WebhookEndpointJpaMapper
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataWebhookEndpointRepository
import com.example.billing.application.port.out.WebhookEndpointRepository
import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.webhook.WebhookEndpoint
import com.example.billing.domain.webhook.WebhookEndpointId
import com.example.billing.domain.webhook.WebhookEndpointStatus
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
class JpaWebhookEndpointRepositoryAdapter(
    private val jpa: SpringDataWebhookEndpointRepository,
) : WebhookEndpointRepository {

    override fun save(endpoint: WebhookEndpoint) {
        jpa.save(WebhookEndpointJpaMapper.toEntity(endpoint))
    }

    override fun findById(id: WebhookEndpointId): Optional<WebhookEndpoint> =
        jpa.findById(id.value).map(WebhookEndpointJpaMapper::toDomain)

    override fun findByCustomer(customerId: CustomerId): List<WebhookEndpoint> =
        jpa.findByCustomerIdOrderByCreatedAtDesc(customerId.value)
            .map(WebhookEndpointJpaMapper::toDomain)

    override fun findActiveByCustomer(customerId: CustomerId): List<WebhookEndpoint> =
        jpa.findByCustomerIdAndStatus(customerId.value, WebhookEndpointStatus.ACTIVE)
            .map(WebhookEndpointJpaMapper::toDomain)
}
