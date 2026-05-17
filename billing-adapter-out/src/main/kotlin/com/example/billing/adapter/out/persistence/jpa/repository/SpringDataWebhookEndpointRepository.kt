package com.example.billing.adapter.out.persistence.jpa.repository

import com.example.billing.adapter.out.persistence.jpa.entity.WebhookEndpointJpaEntity
import com.example.billing.domain.webhook.WebhookEndpointStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpringDataWebhookEndpointRepository : JpaRepository<WebhookEndpointJpaEntity, UUID> {

    fun findByCustomerIdOrderByCreatedAtDesc(customerId: String): List<WebhookEndpointJpaEntity>

    /** 디스패처가 호출 — ACTIVE endpoint 만 대상으로 delivery 생성. */
    fun findByCustomerIdAndStatus(
        customerId: String,
        status: WebhookEndpointStatus,
    ): List<WebhookEndpointJpaEntity>
}
