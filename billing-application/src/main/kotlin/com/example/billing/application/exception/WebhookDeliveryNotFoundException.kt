package com.example.billing.application.exception

import com.example.billing.domain.webhook.WebhookDeliveryId

class WebhookDeliveryNotFoundException(id: WebhookDeliveryId) :
    RuntimeException("webhook delivery not found: $id")
