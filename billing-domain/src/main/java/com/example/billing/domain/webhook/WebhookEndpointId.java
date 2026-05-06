package com.example.billing.domain.webhook;

import java.util.Objects;
import java.util.UUID;

public record WebhookEndpointId(UUID value) {
    public WebhookEndpointId { Objects.requireNonNull(value, "WebhookEndpointId.value"); }
    public static WebhookEndpointId newId() { return new WebhookEndpointId(UUID.randomUUID()); }
    public static WebhookEndpointId of(String s) { return new WebhookEndpointId(UUID.fromString(s)); }
    @Override public String toString() { return value.toString(); }
}
