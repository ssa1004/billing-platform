package com.example.billing.application.port.`in`

import com.example.billing.domain.audit.AuditAction
import com.example.billing.domain.audit.AuditActor
import com.example.billing.domain.audit.AuditEntry
import java.time.Instant

/**
 * Audit 조회 — 운영자 / 감사관 / 컴플레인 응대용.
 *
 * 대부분의 query path 는 4가지로 압축됨:
 *  1. 한 객체에 무슨 일이 있었나 (Invoice / Refund / Wallet 의 timeline)
 *  2. 한 actor 가 무슨 행위를 했나 (운영자 활동 추적 / 비정상 패턴 검사)
 *  3. 한 요청의 모든 audit (분산 추적 join — 한 traceId 의 흐름)
 *  4. 특정 action 의 시간 구간 (예: 모든 환불 / 운영자 export 검사)
 */
interface AuditQueryUseCase {

    fun findByTarget(targetType: String, targetId: String, limit: Int): List<AuditEntry>

    fun findByActor(type: AuditActor.Type, actorId: String, limit: Int): List<AuditEntry>

    fun findByTrace(traceId: String): List<AuditEntry>

    fun findByAction(action: AuditAction, from: Instant, to: Instant, limit: Int): List<AuditEntry>
}
