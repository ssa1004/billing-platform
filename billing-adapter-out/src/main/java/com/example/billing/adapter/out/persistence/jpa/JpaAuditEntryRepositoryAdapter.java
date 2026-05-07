package com.example.billing.adapter.out.persistence.jpa;

import com.example.billing.adapter.out.persistence.jpa.mapper.AuditEntryJpaMapper;
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataAuditEntryRepository;
import com.example.billing.application.port.out.AuditEntryRepository;
import com.example.billing.domain.audit.AuditAction;
import com.example.billing.domain.audit.AuditActor;
import com.example.billing.domain.audit.AuditEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class JpaAuditEntryRepositoryAdapter implements AuditEntryRepository {

    private final SpringDataAuditEntryRepository jpa;

    @Override
    public void save(AuditEntry entry) {
        jpa.save(AuditEntryJpaMapper.toEntity(entry));
    }

    @Override
    public List<AuditEntry> findByTarget(String targetType, String targetId, int limit) {
        return jpa.findByTargetTypeAndTargetIdOrderByOccurredAtDesc(
                        targetType, targetId, PageRequest.of(0, limit))
                .stream()
                .map(AuditEntryJpaMapper::toDomain)
                .toList();
    }

    @Override
    public List<AuditEntry> findByActor(AuditActor.Type type, String actorId, int limit) {
        return jpa.findByActorTypeAndActorIdOrderByOccurredAtDesc(
                        type, actorId, PageRequest.of(0, limit))
                .stream()
                .map(AuditEntryJpaMapper::toDomain)
                .toList();
    }

    @Override
    public List<AuditEntry> findByTrace(String traceId) {
        return jpa.findByTraceId(traceId).stream()
                .map(AuditEntryJpaMapper::toDomain)
                .toList();
    }

    @Override
    public List<AuditEntry> findByActionInRange(AuditAction action, Instant from, Instant to, int limit) {
        return jpa.findByActionAndOccurredAtBetweenOrderByOccurredAtDesc(
                        action, from, to, PageRequest.of(0, limit))
                .stream()
                .map(AuditEntryJpaMapper::toDomain)
                .toList();
    }
}
