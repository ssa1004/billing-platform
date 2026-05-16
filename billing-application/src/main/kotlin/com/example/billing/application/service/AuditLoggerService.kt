package com.example.billing.application.service

import com.example.billing.application.port.`in`.AuditLogger
import com.example.billing.application.port.out.AuditEntryRepository
import com.example.billing.domain.audit.AuditAction
import com.example.billing.domain.audit.AuditActor
import com.example.billing.domain.audit.AuditEntry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * AuditLogger 의 기본 구현 — 호출자 트랜잭션에 참여 (Propagation.REQUIRED, 새 트랜잭션을
 * 만들지 않고 호출자의 것을 그대로 사용) 해서 도메인 변경과 audit 가 같이 commit / 같이
 * rollback 됩니다.
 *
 * traceId 는 SLF4J 의 MDC (Mapped Diagnostic Context, 로그 컨텍스트 저장용 ThreadLocal)
 * 에서 추출 — Spring Boot 의 micrometer-tracing 이 자동으로 `traceId` 키를 채워줍니다
 * (별도 의존성 없이 application 모듈에서 사용 가능).
 */
@Service
open class AuditLoggerService(
    private val auditEntries: AuditEntryRepository,
    private val clock: Clock,
) : AuditLogger {

    @Transactional(propagation = Propagation.REQUIRED)
    override fun log(
        actor: AuditActor,
        action: AuditAction,
        targetType: String,
        targetId: String,
        beforeJson: String?,
        afterJson: String?,
        reason: String?,
    ) {
        val traceId = currentTraceId()
        val entry = AuditEntry.record(
            actor, action, targetType, targetId,
            beforeJson, afterJson, reason, traceId, clock.instant(),
        )
        auditEntries.save(entry)
        // 운영 로그에도 한 줄 — Loki / ELK (로그 수집/검색 시스템) 에서 audit 흐름을 SQL 없이도 추적 가능
        log.info(
            "[audit] {} {} target={}:{} actor={} reason={}",
            action, traceId ?: "-", targetType, targetId,
            actor.id, reason ?: "",
        )
    }

    private fun currentTraceId(): String? =
        try {
            MDC.get(MDC_TRACE_ID)
        } catch (ex: RuntimeException) {
            // MDC 가 비활성이거나 다른 SLF4J 구현이 깔린 경우 — audit 자체는 계속 동작
            null
        }

    companion object {
        private val log = LoggerFactory.getLogger(AuditLoggerService::class.java)

        /** Spring Boot micrometer-tracing 의 표준 MDC 키. */
        private const val MDC_TRACE_ID = "traceId"
    }
}
