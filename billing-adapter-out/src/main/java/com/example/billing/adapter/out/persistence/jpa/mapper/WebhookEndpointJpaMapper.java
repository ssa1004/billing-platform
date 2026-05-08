package com.example.billing.adapter.out.persistence.jpa.mapper;

import com.example.billing.adapter.out.persistence.jpa.entity.WebhookEndpointJpaEntity;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.webhook.WebhookEndpoint;
import com.example.billing.domain.webhook.WebhookEndpointId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.Set;

public final class WebhookEndpointJpaMapper {

    /** static — 의존성 없는 단순 직렬화만 하므로 안전. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private WebhookEndpointJpaMapper() {}

    public static WebhookEndpointJpaEntity toEntity(WebhookEndpoint e) {
        return new WebhookEndpointJpaEntity(
                e.id().value(),
                e.customerId().value(),
                e.url(),
                e.secret(),
                e.previousSecret().orElse(null),
                e.previousSecretValidUntil().orElse(null),
                serializeSet(e.subscribedEventTypes()),
                e.status(),
                e.createdAt(),
                e.updatedAt(),
                e.version()
        );
    }

    public static WebhookEndpoint toDomain(WebhookEndpointJpaEntity e) {
        return WebhookEndpoint.restore(
                new WebhookEndpointId(e.getId()),
                CustomerId.of(e.getCustomerId()),
                e.getUrl(),
                e.getSecret(),
                e.getPreviousSecret(),
                e.getPreviousSecretValidUntil(),
                deserializeSet(e.getSubscribedEventTypesJson()),
                e.getStatus(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getVersion()
        );
    }

    private static String serializeSet(Set<String> set) {
        try {
            return JSON.writeValueAsString(set);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize event types", ex);
        }
    }

    private static Set<String> deserializeSet(String json) {
        if (json == null || json.isBlank() || json.equals("[]")) return new LinkedHashSet<>();
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashSet<String>>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to deserialize event types", ex);
        }
    }
}
