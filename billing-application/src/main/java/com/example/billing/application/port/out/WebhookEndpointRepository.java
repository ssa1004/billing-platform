package com.example.billing.application.port.out;

import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.webhook.WebhookEndpoint;
import com.example.billing.domain.webhook.WebhookEndpointId;

import java.util.List;
import java.util.Optional;

public interface WebhookEndpointRepository {

    void save(WebhookEndpoint endpoint);

    Optional<WebhookEndpoint> findById(WebhookEndpointId id);

    List<WebhookEndpoint> findByCustomer(CustomerId customerId);

    /** 디스패처: 이 customer 의 ACTIVE endpoint 들만 — delivery 생성 대상. */
    List<WebhookEndpoint> findActiveByCustomer(CustomerId customerId);
}
