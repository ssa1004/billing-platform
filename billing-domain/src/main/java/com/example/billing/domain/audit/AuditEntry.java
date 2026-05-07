package com.example.billing.domain.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 한 행위의 영구 기록 (append-only).
 *
 * <p><b>왜 audit log 가 필요한가</b>:
 * <ul>
 *   <li><b>회계 감사 (SOX / 한국 회계기준)</b> — "이 invoice 가 왜 cancel 됐나" 라는 질문에
 *       *몇 년 뒤* 답할 수 있어야 한다. 트랜잭션 로그 / DB 변경 이력 만으론 부족 — *누가*,
 *       *왜* (사유) 가 도메인 모델에 안 박혀 있을 때 audit 가 그 빈 곳을 채운다.</li>
 *   <li><b>PCI-DSS / 정보보호</b> — 결제 / 카드 정보 접근 모든 기록.
 *       사고 (data breach) 시 forensic 의 1순위 자료.</li>
 *   <li><b>운영 분쟁</b> — "내가 환불 요청 안 했는데 왜 처리됐냐" 같은 customer 컴플레인.
 *       actor + ipAddress + traceId 가 답.</li>
 * </ul>
 *
 * <p><b>append-only 의 의미</b>: 한 번 INSERT 된 row 는 *절대* UPDATE / DELETE 안 됨.
 * 도메인 메서드도 setter 없음. 잘못 기록된 항목은 *새 row* (correction entry) 로 정정.
 * 정정 history 도 다시 audit 됨 — 두 row 가 timeline 에 같이 남는 게 *진실의 전체 모습*.</p>
 *
 * <p><b>왜 before/after 를 JSON 으로?</b> 도메인 객체가 다양해 generic 형태가 필요. JSON 이면
 * 어떤 도메인이든 직렬화 가능. 단점은 검색 (특정 필드의 변화 추적) 이 어렵다는 것 — 자주
 * 검색되는 필드는 별도 컬럼 (target_type, target_id) 으로 노출.</p>
 */
public record AuditEntry(
        UUID id,
        AuditActor actor,
        AuditAction action,
        String targetType,        // "Invoice", "Payment", "WebhookEndpoint" 등
        String targetId,          // UUID 또는 자연 키 — string 으로 통일해 join 단순화
        String beforeJson,        // null = 생성 (before 없음)
        String afterJson,         // null = 삭제 (after 없음)
        String reason,            // 자유 텍스트 — "customer requested cancel" 등. nullable.
        String traceId,           // 분산 추적 — 같은 요청의 모든 audit 가 같은 traceId 공유
        Instant occurredAt
) {

    public AuditEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (targetType.isBlank()) throw new IllegalArgumentException("targetType must not be blank");
        if (targetId.isBlank()) throw new IllegalArgumentException("targetId must not be blank");
    }

    /**
     * 새 entry 생성. id 자동 발급. before/after 둘 다 null 허용 (각 의미는 record javadoc 참조).
     */
    public static AuditEntry record(AuditActor actor, AuditAction action, String targetType,
                                    String targetId, String beforeJson, String afterJson,
                                    String reason, String traceId, Instant occurredAt) {
        return new AuditEntry(UUID.randomUUID(), actor, action, targetType, targetId,
                beforeJson, afterJson, reason, traceId, occurredAt);
    }
}
