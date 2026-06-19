package com.example.billing.adapter.out.persistence.jpa

import com.example.billing.adapter.out.persistence.jpa.entity.UsageEventJpaEntity
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataUsageEventRepository
import com.example.billing.application.port.out.UsageEventRepository
import com.example.billing.domain.metering.UsageEvent
import com.example.billing.domain.shared.CustomerId
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class JpaUsageEventRepositoryAdapter(
    private val jpa: SpringDataUsageEventRepository,
) : UsageEventRepository {

    override fun saveIfAbsent(event: UsageEvent): Boolean {
        if (jpa.existsById(event.eventId)) {
            return false
        }
        return try {
            jpa.save(toEntity(event))
            true
        } catch (e: DataIntegrityViolationException) {
            // 동시 INSERT race — UNIQUE constraint 가 잡음. 멱등성으로 무시
            false
        }
    }

    override fun existsById(eventId: UUID): Boolean = jpa.existsById(eventId)

    override fun findInRange(fromInclusive: Instant, toExclusive: Instant, limit: Int): List<UsageEvent> =
        jpa.findByOccurredAtBetweenOrderByOccurredAt(fromInclusive, toExclusive, PageRequest.of(0, limit))
            .map(::toDomain)

    private fun toEntity(e: UsageEvent): UsageEventJpaEntity {
        val entity = UsageEventJpaEntity()
        entity.eventId = e.eventId
        entity.customerId = e.customerId.value
        entity.resourceType = e.resourceType
        entity.quantity = e.quantity
        entity.occurredAt = e.occurredAt
        entity.receivedAt = e.receivedAt
        return entity
    }

    private fun toDomain(entity: UsageEventJpaEntity): UsageEvent = UsageEvent.restore(
        entity.eventId!!,
        CustomerId.of(entity.customerId),
        entity.resourceType,
        entity.quantity,
        entity.occurredAt,
        entity.receivedAt,
    )
}
