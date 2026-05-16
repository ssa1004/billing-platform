package com.example.billing.application.service

import com.example.billing.application.port.`in`.AuditQueryUseCase
import com.example.billing.application.port.out.AuditEntryRepository
import com.example.billing.domain.audit.AuditAction
import com.example.billing.domain.audit.AuditActor
import com.example.billing.domain.audit.AuditEntry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
@Transactional(readOnly = true)
open class AuditQueryService(
    private val entries: AuditEntryRepository,
) : AuditQueryUseCase {

    override fun findByTarget(targetType: String, targetId: String, limit: Int): List<AuditEntry> =
        entries.findByTarget(targetType, targetId, limit)

    override fun findByActor(type: AuditActor.Type, actorId: String, limit: Int): List<AuditEntry> =
        entries.findByActor(type, actorId, limit)

    override fun findByTrace(traceId: String): List<AuditEntry> =
        entries.findByTrace(traceId)

    override fun findByAction(action: AuditAction, from: Instant, to: Instant, limit: Int): List<AuditEntry> =
        entries.findByActionInRange(action, from, to, limit)
}
