package com.example.billing.adapter.web

import com.example.billing.adapter.web.dto.AuditEntryListResponse
import com.example.billing.adapter.web.dto.AuditEntryView
import com.example.billing.application.port.`in`.AuditQueryUseCase
import com.example.billing.domain.audit.AuditAction
import com.example.billing.domain.audit.AuditActor
import com.example.billing.domain.audit.AuditEntry
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 감사 로그 조회 — 운영자 / 감사관 / customer support 가 사용.
 *
 * <p><b>4가지 query 패턴</b>:
 * <ul>
 *   <li>{@code GET ?targetType=Invoice&targetId=...} — 한 객체에 무슨 일이 있었나 (가장 흔함)</li>
 *   <li>{@code GET ?actorType=OPERATOR&actorId=alice} — 운영자 활동 추적</li>
 *   <li>{@code GET ?traceId=abc123} — 한 요청의 모든 audit (분산 추적 join)</li>
 *   <li>{@code GET ?action=REFUND_APPROVED&from=...&to=...} — 특정 행위 시간 구간 (SIEM 연동)</li>
 * </ul>
 *
 * <p><b>OWASP API5 Broken Function Level Auth</b>: audit row 는 다른 customer 자원 (refundId,
 * payment 금액, 운영자 이름 등) 을 그대로 포함한다. enumeration 으로 PII / 운영 데이터가
 * 흘러나가는 사고를 막기 위해 controller 자체를 ADMIN 전용. customer 자기 자원만 보는 흐름은
 * 도메인 별 read endpoint (Invoice / Payment / Refund 등) 가 따로 제공.</p>
 */
@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "audit", description = "감사 로그 조회 (append-only)")
@PreAuthorize("hasRole('admin')")
class AuditController(
    private val auditQuery: AuditQueryUseCase,
) {

    @GetMapping
    @Operation(summary = "audit 조회 — 4가지 query 패턴 중 하나로 호출")
    fun query(
        @RequestParam(required = false) targetType: String?,
        @RequestParam(required = false) targetId: String?,
        @RequestParam(required = false) actorType: String?,
        @RequestParam(required = false) actorId: String?,
        @RequestParam(required = false) traceId: String?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) from: String?,         // ISO-8601
        @RequestParam(required = false) to: String?,           // ISO-8601
        @RequestParam(defaultValue = "100") limit: Int,
    ): ResponseEntity<AuditEntryListResponse> {
        val bounded = limit.coerceIn(1, MAX_LIMIT)
        val entries = when {
            // 1. trace 한 요청의 audit 모두
            traceId != null -> auditQuery.findByTrace(traceId)

            // 2. 객체 timeline
            targetType != null && targetId != null ->
                auditQuery.findByTarget(targetType, targetId, bounded)

            // 3. actor 활동
            actorType != null && actorId != null ->
                auditQuery.findByActor(AuditActor.Type.valueOf(actorType), actorId, bounded)

            // 4. 특정 action 시간 구간
            action != null && from != null && to != null ->
                auditQuery.findByAction(
                    AuditAction.valueOf(action),
                    Instant.parse(from), Instant.parse(to), bounded
                )

            // 어떤 query 도 매칭 안 되면 빈 응답 — 위험한 전체 스캔 회피
            else -> emptyList()
        }
        return ResponseEntity.ok(AuditEntryListResponse(items = entries.map(::toView)))
    }

    private fun toView(e: AuditEntry): AuditEntryView = AuditEntryView(
        id = e.id.toString(),
        actorType = e.actor.type.name,
        actorId = e.actor.id,
        actorIp = e.actor.ipAddress,
        action = e.action.name,
        targetType = e.targetType,
        targetId = e.targetId,
        beforeJson = e.beforeJson,
        afterJson = e.afterJson,
        reason = e.reason,
        traceId = e.traceId,
        occurredAt = e.occurredAt.toString(),
    )

    companion object {
        /** OWASP API4 — Unrestricted Resource Consumption cap. */
        private const val MAX_LIMIT = 500
    }
}
