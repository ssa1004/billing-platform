package com.example.billing.adapter.out.persistence.jpa.repository;

import com.example.billing.adapter.out.persistence.jpa.entity.WebhookEndpointJpaEntity;
import com.example.billing.domain.webhook.WebhookEndpointStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataWebhookEndpointRepository extends JpaRepository<WebhookEndpointJpaEntity, UUID> {

    List<WebhookEndpointJpaEntity> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    /** 디스패처가 호출 — ACTIVE endpoint 만 대상으로 delivery 생성. */
    List<WebhookEndpointJpaEntity> findByCustomerIdAndStatus(String customerId, WebhookEndpointStatus status);
}
