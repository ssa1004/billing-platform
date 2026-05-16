package com.example.billing.application.port.out

import com.example.billing.domain.audit.AuditAction
import com.example.billing.domain.audit.AuditActor
import com.example.billing.domain.audit.AuditEntry
import java.time.Instant

/**
 * Audit entry 저장 / 조회.
 *
 * **save 만** 있고 update / delete 는 의도적으로 없음 — append-only.
 * 누군가 잘못된 entry 를 정정하려면 새 entry 를 INSERT 해야 한다 (timeline 에 두 row 다 남음).
 */
interface AuditEntryRepository {

    fun save(entry: AuditEntry)

    /** 특정 객체의 변경 timeline. */
    fun findByTarget(targetType: String, targetId: String, limit: Int): List<AuditEntry>

    /** 특정 actor (운영자 / 시스템) 의 행위 timeline. */
    fun findByActor(type: AuditActor.Type, actorId: String, limit: Int): List<AuditEntry>

    /** 분산 추적 — 한 요청의 모든 audit. */
    fun findByTrace(traceId: String): List<AuditEntry>

    /** 특정 action 시간 구간 (SIEM 연동 / 정기 정합성 검사). */
    fun findByActionInRange(action: AuditAction, from: Instant, to: Instant, limit: Int): List<AuditEntry>
}
