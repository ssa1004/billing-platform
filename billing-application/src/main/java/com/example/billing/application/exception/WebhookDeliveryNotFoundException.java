package com.example.billing.application.exception;

import com.example.billing.domain.webhook.WebhookDeliveryId;

public class WebhookDeliveryNotFoundException extends RuntimeException {
    public WebhookDeliveryNotFoundException(WebhookDeliveryId id) {
        super("webhook delivery not found: " + id);
    }
}
