package com.example.billing.application.service;

import com.example.billing.application.port.in.AuditLogger;
import com.example.billing.application.port.out.AuditEntryRepository;
import com.example.billing.domain.audit.AuditAction;
import com.example.billing.domain.audit.AuditActor;
import com.example.billing.domain.audit.AuditEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * AuditLogger 의 기본 구현 — 호출자 트랜잭션에 *참여* (REQUIRED) 해 같이 commit/rollback.
 *
 * <p>traceId 는 SLF4J MDC 에서 추출 — Spring Boot 의 micrometer-tracing 이 자동으로
 * {@code traceId} 키에 채워준다 (별도 의존성 없이 application 모듈에서 사용 가능).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLoggerService implements AuditLogger {

    /** Spring Boot micrometer-tracing 의 표준 MDC 키. */
    private static final String MDC_TRACE_ID = "traceId";

    private final AuditEntryRepository auditEntries;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void log(AuditActor actor, AuditAction action, String targetType, String targetId,
                    String beforeJson, String afterJson, String reason) {
        String traceId = currentTraceId();
        AuditEntry entry = AuditEntry.record(
                actor, action, targetType, targetId,
                beforeJson, afterJson, reason, traceId, clock.instant()
        );
        auditEntries.save(entry);
        // 운영 로그에도 한 줄 — Loki / ELK 에서 audit 흐름을 SQL 모르고도 추적 가능
        log.info("[audit] {} {} target={}:{} actor={} reason={}",
                action, traceId != null ? traceId : "-", targetType, targetId,
                actor.id(), reason != null ? reason : "");
    }

    private static String currentTraceId() {
        try {
            return MDC.get(MDC_TRACE_ID);
        } catch (RuntimeException ex) {
            // MDC 비활성 / 다른 SLF4J 구현 — audit 자체는 계속 동작
            return null;
        }
    }
}
