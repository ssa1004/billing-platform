package com.example.billing.adapter.out.persistence.jpa.mapper

import com.example.billing.adapter.out.persistence.jpa.entity.WebhookEndpointJpaEntity
import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.webhook.WebhookEndpoint
import com.example.billing.domain.webhook.WebhookEndpointId
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.LinkedHashSet

object WebhookEndpointJpaMapper {

    /** static — 의존성 없는 단순 직렬화만 하므로 안전. */
    private val JSON = ObjectMapper()

    @JvmStatic
    fun toEntity(e: WebhookEndpoint): WebhookEndpointJpaEntity = WebhookEndpointJpaEntity(
        e.id.value,
        e.customerId.value,
        e.url,
        e.secret,
        e.previousSecret().orElse(null),
        e.previousSecretValidUntil().orElse(null),
        serializeSet(e.subscribedEventTypes()),
        e.status,
        e.createdAt,
        e.updatedAt,
        e.version,
    )

    @JvmStatic
    fun toDomain(e: WebhookEndpointJpaEntity): WebhookEndpoint = WebhookEndpoint.restore(
        WebhookEndpointId(e.id!!),
        CustomerId.of(e.customerId),
        e.url,
        e.secret,
        e.previousSecret,
        e.previousSecretValidUntil,
        deserializeSet(e.subscribedEventTypesJson),
        e.status,
        e.createdAt,
        e.updatedAt,
        e.version,
    )

    private fun serializeSet(set: Set<String>): String {
        try {
            return JSON.writeValueAsString(set)
        } catch (ex: JsonProcessingException) {
            throw IllegalStateException("failed to serialize event types", ex)
        }
    }

    private fun deserializeSet(json: String?): Set<String> {
        if (json.isNullOrBlank() || json == "[]") return LinkedHashSet()
        try {
            return JSON.readValue(json, object : TypeReference<LinkedHashSet<String>>() {})
        } catch (ex: JsonProcessingException) {
            throw IllegalStateException("failed to deserialize event types", ex)
        }
    }
}
