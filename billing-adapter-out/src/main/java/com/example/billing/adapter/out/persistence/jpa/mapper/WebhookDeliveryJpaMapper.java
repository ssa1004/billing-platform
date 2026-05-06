package com.example.billing.adapter.out.persistence.jpa.mapper;

import com.example.billing.adapter.out.persistence.jpa.entity.WebhookDeliveryJpaEntity;
import com.example.billing.domain.webhook.WebhookDelivery;
import com.example.billing.domain.webhook.WebhookDeliveryId;
import com.example.billing.domain.webhook.WebhookEndpointId;

public final class WebhookDeliveryJpaMapper {

    private WebhookDeliveryJpaMapper() {}

    public static WebhookDeliveryJpaEntity toEntity(WebhookDelivery d) {
        return new WebhookDeliveryJpaEntity(
                d.id().value(),
                d.endpointId().value(),
                d.eventType(),
                d.payload(),
                d.status(),
                d.attemptCount(),
                d.nextAttemptAt(),
                d.lastResponseStatus(),
                d.lastError(),
                d.createdAt(),
                d.updatedAt(),
                d.deliveredAt(),
                d.version()
        );
    }

    public static WebhookDelivery toDomain(WebhookDeliveryJpaEntity e) {
        return WebhookDelivery.restore(
                new WebhookDeliveryId(e.getId()),
                new WebhookEndpointId(e.getEndpointId()),
                e.getEventType(),
                e.getPayload(),
                e.getStatus(),
                e.getAttemptCount(),
                e.getNextAttemptAt(),
                e.getLastResponseStatus(),
                e.getLastError(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getDeliveredAt(),
                e.getVersion()
        );
    }
}
