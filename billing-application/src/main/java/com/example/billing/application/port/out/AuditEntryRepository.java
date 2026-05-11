package com.example.billing.application.port.out;

import com.example.billing.domain.audit.AuditAction;
import com.example.billing.domain.audit.AuditActor;
import com.example.billing.domain.audit.AuditEntry;

import java.time.Instant;
import java.util.List;

/**
 * Audit entry 저장 / 조회.
 *
 * <p><b>save 만</b> 있고 update / delete 는 의도적으로 없음 — append-only.
 * 누군가 잘못된 entry 를 정정하려면 새 entry 를 INSERT 해야 한다 (timeline 에 두 row 다 남음).</p>
 */
public interface AuditEntryRepository {

    void save(AuditEntry entry);

    /** 특정 객체의 변경 timeline. */
    List<AuditEntry> findByTarget(String targetType, String targetId, int limit);

    /** 특정 actor (운영자 / 시스템) 의 행위 timeline. */
    List<AuditEntry> findByActor(AuditActor.Type type, String actorId, int limit);

    /** 분산 추적 — 한 요청의 모든 audit. */
    List<AuditEntry> findByTrace(String traceId);

    /** 특정 action 시간 구간 (SIEM 연동 / 정기 정합성 검사). */
    List<AuditEntry> findByActionInRange(AuditAction action, Instant from, Instant to, int limit);
}
