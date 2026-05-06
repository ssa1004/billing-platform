package com.example.billing.domain.webhook;

import java.util.Objects;
import java.util.UUID;

public record WebhookDeliveryId(UUID value) {
    public WebhookDeliveryId { Objects.requireNonNull(value, "WebhookDeliveryId.value"); }
    public static WebhookDeliveryId newId() { return new WebhookDeliveryId(UUID.randomUUID()); }
    public static WebhookDeliveryId of(String s) { return new WebhookDeliveryId(UUID.fromString(s)); }
    @Override public String toString() { return value.toString(); }
}
