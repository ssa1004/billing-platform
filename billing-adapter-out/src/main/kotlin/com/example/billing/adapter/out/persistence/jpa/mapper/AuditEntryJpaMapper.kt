package com.example.billing.adapter.out.persistence.jpa.mapper

import com.example.billing.adapter.out.persistence.jpa.entity.AuditEntryJpaEntity
import com.example.billing.domain.audit.AuditActor
import com.example.billing.domain.audit.AuditEntry

object AuditEntryJpaMapper {

    @JvmStatic
    fun toEntity(e: AuditEntry): AuditEntryJpaEntity = AuditEntryJpaEntity(
        e.id,
        e.actor.type,
        e.actor.id,
        e.actor.ipAddress,
        e.actor.userAgent,
        e.action,
        e.targetType,
        e.targetId,
        e.beforeJson,
        e.afterJson,
        e.reason,
        e.traceId,
        e.occurredAt,
    )

    @JvmStatic
    fun toDomain(e: AuditEntryJpaEntity): AuditEntry {
        val actor = AuditActor(
            e.actorType,
            e.actorId,
            e.actorIp,
            e.actorUserAgent,
        )
        return AuditEntry(
            e.id!!,
            actor,
            e.action,
            e.targetType,
            e.targetId,
            e.beforeJson,
            e.afterJson,
            e.reason,
            e.traceId,
            e.occurredAt,
        )
    }
}
