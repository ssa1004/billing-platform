package com.example.billing.application.port.in;

import com.example.billing.domain.audit.AuditAction;
import com.example.billing.domain.audit.AuditActor;

/**
 * Audit log 기록 — application service 가 행위 직후 호출.
 *
 * <p><b>왜 in port 인가</b>: audit 기록은 비즈니스 의도 의 일부. "이 환불은 운영자 X 가 사유 Y
 * 로 승인했다" 는 데이터를 application 이 명시적으로 알려주는 게 맞다. AOP / 자동 listener
 * 로 처리하면 왜 (사유) 를 잃는다.</p>
 *
 * <p><b>같은 트랜잭션</b>: 호출자의 트랜잭션 안에서 INSERT — 도메인 변경과 audit 가 같이
 * commit / 같이 rollback. "도메인은 바뀌었는데 audit 는 누락" 같은 정합 사고 회피.</p>
 *
 * <p><b>실패 모드</b>: audit 저장 실패는 비즈니스 트랜잭션 자체를 깨야 한다. audit 없이는
 * "감사 가능성" 이 사라지므로 데이터 정합 깨진 거나 마찬가지. 따라서 RuntimeException 그대로
 * 전파 — 호출자 트랜잭션 rollback.</p>
 */
public interface AuditLogger {

    /**
     * 단일 audit entry 기록. before/after 는 호출자가 도메인 객체를 JSON 직렬화해 전달.
     * 둘 다 null 가능 (생성=before null / 삭제=after null).
     */
    void log(AuditActor actor, AuditAction action, String targetType, String targetId,
             String beforeJson, String afterJson, String reason);
}
