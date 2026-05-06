package com.example.billing.adapter.out.persistence.jpa;

import com.example.billing.adapter.out.persistence.jpa.mapper.WebhookEndpointJpaMapper;
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataWebhookEndpointRepository;
import com.example.billing.application.port.out.WebhookEndpointRepository;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.webhook.WebhookEndpoint;
import com.example.billing.domain.webhook.WebhookEndpointId;
import com.example.billing.domain.webhook.WebhookEndpointStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaWebhookEndpointRepositoryAdapter implements WebhookEndpointRepository {

    private final SpringDataWebhookEndpointRepository jpa;

    @Override
    public void save(WebhookEndpoint endpoint) {
        jpa.save(WebhookEndpointJpaMapper.toEntity(endpoint));
    }

    @Override
    public Optional<WebhookEndpoint> findById(WebhookEndpointId id) {
        return jpa.findById(id.value()).map(WebhookEndpointJpaMapper::toDomain);
    }

    @Override
    public List<WebhookEndpoint> findByCustomer(CustomerId customerId) {
        return jpa.findByCustomerIdOrderByCreatedAtDesc(customerId.value()).stream()
                .map(WebhookEndpointJpaMapper::toDomain)
                .toList();
    }

    @Override
    public List<WebhookEndpoint> findActiveByCustomer(CustomerId customerId) {
        return jpa.findByCustomerIdAndStatus(customerId.value(), WebhookEndpointStatus.ACTIVE).stream()
                .map(WebhookEndpointJpaMapper::toDomain)
                .toList();
    }
}
