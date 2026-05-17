package com.example.billing.adapter.out.persistence.jpa.mapper

import com.example.billing.adapter.out.persistence.jpa.entity.WebhookDeliveryJpaEntity
import com.example.billing.domain.webhook.WebhookDelivery
import com.example.billing.domain.webhook.WebhookDeliveryId
import com.example.billing.domain.webhook.WebhookEndpointId

object WebhookDeliveryJpaMapper {

    @JvmStatic
    fun toEntity(d: WebhookDelivery): WebhookDeliveryJpaEntity = WebhookDeliveryJpaEntity(
        d.id.value,
        d.endpointId.value,
        d.eventType,
        d.payload,
        d.status,
        d.attemptCount,
        d.nextAttemptAt,
        d.lastResponseStatus,
        d.lastError,
        d.createdAt,
        d.updatedAt,
        d.deliveredAt,
        d.version,
    )

    @JvmStatic
    fun toDomain(e: WebhookDeliveryJpaEntity): WebhookDelivery = WebhookDelivery.restore(
        WebhookDeliveryId(e.id!!),
        WebhookEndpointId(e.endpointId!!),
        e.eventType,
        e.payload,
        e.status,
        e.attemptCount,
        e.nextAttemptAt,
        e.lastResponseStatus,
        e.lastError,
        e.createdAt,
        e.updatedAt,
        e.deliveredAt,
        e.version,
    )
}
