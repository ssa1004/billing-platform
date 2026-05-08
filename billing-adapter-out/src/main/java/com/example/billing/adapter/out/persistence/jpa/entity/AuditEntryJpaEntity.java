package com.example.billing.adapter.out.persistence.jpa.entity;

import com.example.billing.domain.audit.AuditAction;
import com.example.billing.domain.audit.AuditActor;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit entry persistence.
 *
 * <p><b>{@code @Setter} 없음 — 의도</b>: append-only 라 영속 후 mutate 금지. 새 row 만 INSERT.</p>
 *
 * <p><b>2단 방어</b>:
 * <ol>
 *   <li>도메인/Lombok — setter 가 아예 없어 정상 코드 경로로는 수정 불가.</li>
 *   <li>JPA listener ({@link AuditAppendOnlyGuard}) — 누군가 EntityManager 직접 접근으로
 *       UPDATE / DELETE 를 시도해도 {@code @PreUpdate} / {@code @PreRemove} 에서 예외.</li>
 * </ol>
 *
 * <p>DB 단 trigger 까지 가는 것이 가장 강력하지만 H2 (dev/test) 와 Postgres (prod) 의 trigger
 * 문법이 달라 portability 가 떨어집니다. 이 프로젝트에서는 JPA 단에서 막고, 운영 DB 는
 * 별도 vendor-specific migration 으로 trigger 를 추가할 수 있도록 구조만 열어둡니다.</p>
 */
@Entity
@Table(name = "audit_entries")
@EntityListeners(AuditAppendOnlyGuard.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
public class AuditEntryJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 16)
    private AuditActor.Type actorType;

    @Column(name = "actor_id", nullable = false, length = 128)
    private String actorId;

    @Column(name = "actor_ip", length = 64)
    private String actorIp;

    @Column(name = "actor_user_agent", length = 512)
    private String actorUserAgent;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 64)
    private AuditAction action;

    @Column(name = "target_type", nullable = false, length = 64)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 128)
    private String targetId;

    @Column(name = "before_json", columnDefinition = "TEXT")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "TEXT")
    private String afterJson;

    @Column(name = "reason", length = 1024)
    private String reason;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
