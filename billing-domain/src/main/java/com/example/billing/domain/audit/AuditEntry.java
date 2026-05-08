package com.example.billing.domain.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 한 행위의 영구 기록 (append-only, 한 번 적으면 수정/삭제 안 함, 추가만).
 *
 * <p><b>왜 audit log 가 필요한가</b>:
 * <ul>
 *   <li><b>회계 감사 (SOX, 미국 상장 기업 회계 책임법 / 한국 회계기준)</b> — "이 invoice 가
 *       왜 cancel 됐나" 같은 질문에 *몇 년 뒤* 답할 수 있어야 함. 트랜잭션 로그 / DB 변경
 *       이력만으론 부족 — *누가*, *왜* (사유) 가 도메인 모델에 안 박혀 있을 때 audit 가 그
 *       빈 곳을 채워줍니다.</li>
 *   <li><b>PCI-DSS (카드 정보 보안 표준) / 정보보호</b> — 결제 / 카드 정보 접근 기록 전부.
 *       정보 유출 사고 (data breach) 시 forensic (사고 후 원인 추적) 의 1순위 자료.</li>
 *   <li><b>운영 분쟁</b> — "내가 환불 요청 안 했는데 왜 처리됐냐" 같은 customer 컴플레인.
 *       actor + ipAddress + traceId 가 답해줍니다.</li>
 * </ul>
 *
 * <p><b>append-only 의 의미</b>: 한 번 INSERT 된 row 는 *절대* UPDATE / DELETE 하지 않습니다.
 * 도메인 메서드에도 setter 가 없습니다. 잘못 기록된 항목은 *새 row* (정정 entry, correction
 * entry) 로 정정합니다. 정정 history 도 다시 audit 되어 두 row 가 timeline 에 같이 남습니다
 * — 그 timeline 전체가 *진실의 전체 모습*.</p>
 *
 * <p><b>왜 before/after 를 JSON 으로?</b> 한 audit 테이블이 Invoice / Refund / Credit / Wallet
 * 등 여러 도메인의 변경을 다 담아야 하는데, 도메인마다 컬럼 구조가 달라 일반 (generic) 표현이
 * 필요합니다. JSON 으로 dump 하면 어떤 도메인이든 같은 형식으로 적을 수 있음. 단점은 *특정
 * 필드의 변화만 골라 검색* 하기 어렵다는 것 — JSON 안을 SQL 로 풀어보려면 운영 DB 의
 * jsonb 연산자가 필요해 비용이 큼. 자주 검색되는 필드 (target_type, target_id, action) 는
 * 별도 컬럼으로 빼서 인덱스를 직접 걸었습니다 (V9 migration 의 idx_audit_* 4종).</p>
 */
public record AuditEntry(
        UUID id,
        AuditActor actor,
        AuditAction action,
        String targetType,        // "Invoice", "Payment", "WebhookEndpoint" 등
        String targetId,          // UUID 또는 자연 키 — string 으로 통일해 join 단순화
        String beforeJson,        // null 이면 생성 (before 없음)
        String afterJson,         // null 이면 삭제 (after 없음)
        String reason,            // 자유 텍스트 — "customer requested cancel" 등. null 가능.
        String traceId,           // 분산 추적 ID — 한 요청을 거친 모든 단계가 같은 값 공유
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
