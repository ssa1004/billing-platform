package com.example.billing.adapter.out.persistence.jpa.mapper;

import com.example.billing.adapter.out.persistence.jpa.entity.AuditEntryJpaEntity;
import com.example.billing.domain.audit.AuditActor;
import com.example.billing.domain.audit.AuditEntry;

public final class AuditEntryJpaMapper {

    private AuditEntryJpaMapper() {}

    public static AuditEntryJpaEntity toEntity(AuditEntry e) {
        return new AuditEntryJpaEntity(
                e.id(),
                e.actor().type(),
                e.actor().id(),
                e.actor().ipAddress(),
                e.actor().userAgent(),
                e.action(),
                e.targetType(),
                e.targetId(),
                e.beforeJson(),
                e.afterJson(),
                e.reason(),
                e.traceId(),
                e.occurredAt()
        );
    }

    public static AuditEntry toDomain(AuditEntryJpaEntity e) {
        AuditActor actor = new AuditActor(
                e.getActorType(),
                e.getActorId(),
                e.getActorIp(),
                e.getActorUserAgent()
        );
        return new AuditEntry(
                e.getId(),
                actor,
                e.getAction(),
                e.getTargetType(),
                e.getTargetId(),
                e.getBeforeJson(),
                e.getAfterJson(),
                e.getReason(),
                e.getTraceId(),
                e.getOccurredAt()
        );
    }
}
