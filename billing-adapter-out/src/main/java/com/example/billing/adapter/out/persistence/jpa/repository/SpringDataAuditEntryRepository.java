package com.example.billing.adapter.out.persistence.jpa.repository;

import com.example.billing.adapter.out.persistence.jpa.entity.AuditEntryJpaEntity;
import com.example.billing.domain.audit.AuditAction;
import com.example.billing.domain.audit.AuditActor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringDataAuditEntryRepository extends JpaRepository<AuditEntryJpaEntity, UUID> {

    List<AuditEntryJpaEntity> findByTargetTypeAndTargetIdOrderByOccurredAtDesc(
            String targetType, String targetId, Pageable pageable);

    List<AuditEntryJpaEntity> findByActorTypeAndActorIdOrderByOccurredAtDesc(
            AuditActor.Type actorType, String actorId, Pageable pageable);

    List<AuditEntryJpaEntity> findByTraceId(String traceId);

    List<AuditEntryJpaEntity> findByActionAndOccurredAtBetweenOrderByOccurredAtDesc(
            AuditAction action, Instant from, Instant to, Pageable pageable);
}
