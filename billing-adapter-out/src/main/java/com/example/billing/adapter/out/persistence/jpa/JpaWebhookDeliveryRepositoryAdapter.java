package com.example.billing.adapter.out.persistence.jpa;

import com.example.billing.adapter.out.persistence.jpa.mapper.WebhookDeliveryJpaMapper;
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataWebhookDeliveryRepository;
import com.example.billing.application.port.out.WebhookDeliveryRepository;
import com.example.billing.domain.webhook.WebhookDelivery;
import com.example.billing.domain.webhook.WebhookDeliveryId;
import com.example.billing.domain.webhook.WebhookDeliveryStatus;
import com.example.billing.domain.webhook.WebhookEndpointId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaWebhookDeliveryRepositoryAdapter implements WebhookDeliveryRepository {

    private final SpringDataWebhookDeliveryRepository jpa;

    @Override
    public void save(WebhookDelivery delivery) {
        jpa.save(WebhookDeliveryJpaMapper.toEntity(delivery));
    }

    @Override
    public Optional<WebhookDelivery> findById(WebhookDeliveryId id) {
        return jpa.findById(id.value()).map(WebhookDeliveryJpaMapper::toDomain);
    }

    @Override
    public List<WebhookDelivery> claimPending(Instant now, int limit) {
        return jpa.claimPending(now, PageRequest.of(0, limit)).stream()
                .map(WebhookDeliveryJpaMapper::toDomain)
                .toList();
    }

    @Override
    public List<WebhookDelivery> findByEndpoint(WebhookEndpointId endpointId, int limit) {
        return jpa.findByEndpointIdOrderByCreatedAtDesc(endpointId.value(), PageRequest.of(0, limit))
                .stream()
                .map(WebhookDeliveryJpaMapper::toDomain)
                .toList();
    }

    @Override
    public List<WebhookDelivery> findByStatus(WebhookDeliveryStatus status, int limit) {
        return jpa.findByStatusOrderByCreatedAtDesc(status, PageRequest.of(0, limit)).stream()
                .map(WebhookDeliveryJpaMapper::toDomain)
                .toList();
    }
}
