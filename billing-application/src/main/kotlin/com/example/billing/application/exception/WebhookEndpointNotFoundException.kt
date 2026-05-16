package com.example.billing.application.exception

import com.example.billing.domain.webhook.WebhookEndpointId

class WebhookEndpointNotFoundException(id: WebhookEndpointId) :
    RuntimeException("webhook endpoint not found: $id")
