package com.example.billing.application.service;

import com.example.billing.application.port.in.AuditQueryUseCase;
import com.example.billing.application.port.out.AuditEntryRepository;
import com.example.billing.domain.audit.AuditAction;
import com.example.billing.domain.audit.AuditActor;
import com.example.billing.domain.audit.AuditEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditQueryService implements AuditQueryUseCase {

    private final AuditEntryRepository entries;

    @Override
    public List<AuditEntry> findByTarget(String targetType, String targetId, int limit) {
        return entries.findByTarget(targetType, targetId, limit);
    }

    @Override
    public List<AuditEntry> findByActor(AuditActor.Type type, String actorId, int limit) {
        return entries.findByActor(type, actorId, limit);
    }

    @Override
    public List<AuditEntry> findByTrace(String traceId) {
        return entries.findByTrace(traceId);
    }

    @Override
    public List<AuditEntry> findByAction(AuditAction action, Instant from, Instant to, int limit) {
        return entries.findByActionInRange(action, from, to, limit);
    }
}
