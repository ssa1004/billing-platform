package com.example.billing.application.exception;

import com.example.billing.domain.webhook.WebhookEndpointId;

public class WebhookEndpointNotFoundException extends RuntimeException {
    public WebhookEndpointNotFoundException(WebhookEndpointId id) {
        super("webhook endpoint not found: " + id);
    }
}
