package com.example.billing.adapter.out.persistence.jpa.repository

import com.example.billing.adapter.out.persistence.jpa.entity.AuditEntryJpaEntity
import com.example.billing.domain.audit.AuditAction
import com.example.billing.domain.audit.AuditActor
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface SpringDataAuditEntryRepository : JpaRepository<AuditEntryJpaEntity, UUID> {

    fun findByTargetTypeAndTargetIdOrderByOccurredAtDesc(
        targetType: String,
        targetId: String,
        pageable: Pageable,
    ): List<AuditEntryJpaEntity>

    fun findByActorTypeAndActorIdOrderByOccurredAtDesc(
        actorType: AuditActor.Type,
        actorId: String,
        pageable: Pageable,
    ): List<AuditEntryJpaEntity>

    fun findByTraceId(traceId: String): List<AuditEntryJpaEntity>

    fun findByActionAndOccurredAtBetweenOrderByOccurredAtDesc(
        action: AuditAction,
        from: Instant,
        to: Instant,
        pageable: Pageable,
    ): List<AuditEntryJpaEntity>
}
