package com.example.billing.application.port.out

import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.webhook.WebhookEndpoint
import com.example.billing.domain.webhook.WebhookEndpointId
import java.util.Optional

interface WebhookEndpointRepository {

    fun save(endpoint: WebhookEndpoint)

    fun findById(id: WebhookEndpointId): Optional<WebhookEndpoint>

    fun findByCustomer(customerId: CustomerId): List<WebhookEndpoint>

    /** 디스패처: 이 customer 의 ACTIVE endpoint 들만 — delivery 생성 대상. */
    fun findActiveByCustomer(customerId: CustomerId): List<WebhookEndpoint>
}
