package com.example.billing.adapter.out.persistence.jpa

import com.example.billing.adapter.out.persistence.jpa.mapper.AuditEntryJpaMapper
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataAuditEntryRepository
import com.example.billing.application.port.out.AuditEntryRepository
import com.example.billing.domain.audit.AuditAction
import com.example.billing.domain.audit.AuditActor
import com.example.billing.domain.audit.AuditEntry
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class JpaAuditEntryRepositoryAdapter(
    private val jpa: SpringDataAuditEntryRepository,
) : AuditEntryRepository {

    override fun save(entry: AuditEntry) {
        jpa.save(AuditEntryJpaMapper.toEntity(entry))
    }

    override fun findByTarget(targetType: String, targetId: String, limit: Int): List<AuditEntry> =
        jpa.findByTargetTypeAndTargetIdOrderByOccurredAtDesc(targetType, targetId, PageRequest.of(0, limit))
            .map(AuditEntryJpaMapper::toDomain)

    override fun findByActor(type: AuditActor.Type, actorId: String, limit: Int): List<AuditEntry> =
        jpa.findByActorTypeAndActorIdOrderByOccurredAtDesc(type, actorId, PageRequest.of(0, limit))
            .map(AuditEntryJpaMapper::toDomain)

    override fun findByTrace(traceId: String): List<AuditEntry> =
        jpa.findByTraceId(traceId).map(AuditEntryJpaMapper::toDomain)

    override fun findByActionInRange(action: AuditAction, from: Instant, to: Instant, limit: Int): List<AuditEntry> =
        jpa.findByActionAndOccurredAtBetweenOrderByOccurredAtDesc(action, from, to, PageRequest.of(0, limit))
            .map(AuditEntryJpaMapper::toDomain)
}
