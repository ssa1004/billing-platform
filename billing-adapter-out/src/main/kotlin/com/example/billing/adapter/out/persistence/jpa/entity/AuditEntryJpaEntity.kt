package com.example.billing.adapter.out.persistence.jpa.entity

import com.example.billing.domain.audit.AuditAction
import com.example.billing.domain.audit.AuditActor
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Audit entry persistence.
 *
 * `@Setter` 없음 — 의도: append-only 라 영속 후 mutate 금지. 새 row 만 INSERT.
 *
 * 2단 방어:
 *  - 도메인/Lombok — setter 가 아예 없어 정상 코드 경로로는 수정 불가.
 *  - JPA listener ([AuditAppendOnlyGuard]) — 누군가 EntityManager 직접 접근으로
 *    UPDATE / DELETE 를 시도해도 `@PreUpdate` / `@PreRemove` 에서 예외.
 *
 * DB 단 trigger 까지 가는 것이 가장 강력하지만 H2 (dev/test) 와 Postgres (prod) 의 trigger
 * 문법이 달라 portability 가 떨어집니다. 이 프로젝트에서는 JPA 단에서 막고, 운영 DB 는
 * 별도 vendor-specific migration 으로 trigger 를 추가할 수 있도록 구조만 열어둡니다.
 */
@Entity
@Table(name = "audit_entries")
@EntityListeners(AuditAppendOnlyGuard::class)
class AuditEntryJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null
        private set

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 16)
    var actorType: AuditActor.Type = AuditActor.Type.USER
        private set

    @Column(name = "actor_id", nullable = false, length = 128)
    var actorId: String = ""
        private set

    @Column(name = "actor_ip", length = 64)
    var actorIp: String? = null
        private set

    @Column(name = "actor_user_agent", length = 512)
    var actorUserAgent: String? = null
        private set

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 64)
    var action: AuditAction = AuditAction.INVOICE_ISSUED
        private set

    @Column(name = "target_type", nullable = false, length = 64)
    var targetType: String = ""
        private set

    @Column(name = "target_id", nullable = false, length = 128)
    var targetId: String = ""
        private set

    @Column(name = "before_json", columnDefinition = "TEXT")
    var beforeJson: String? = null
        private set

    @Column(name = "after_json", columnDefinition = "TEXT")
    var afterJson: String? = null
        private set

    @Column(name = "reason", length = 1024)
    var reason: String? = null
        private set

    @Column(name = "trace_id", length = 64)
    var traceId: String? = null
        private set

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant = Instant.EPOCH
        private set

    /** JPA 가 요구하는 no-arg. */
    constructor()

    /** 도메인 → entity 변환용 all-args. setter 가 없어 INSERT 직전 한 번만 채워짐. */
    constructor(
        id: UUID,
        actorType: AuditActor.Type,
        actorId: String,
        actorIp: String?,
        actorUserAgent: String?,
        action: AuditAction,
        targetType: String,
        targetId: String,
        beforeJson: String?,
        afterJson: String?,
        reason: String?,
        traceId: String?,
        occurredAt: Instant,
    ) {
        this.id = id
        this.actorType = actorType
        this.actorId = actorId
        this.actorIp = actorIp
        this.actorUserAgent = actorUserAgent
        this.action = action
        this.targetType = targetType
        this.targetId = targetId
        this.beforeJson = beforeJson
        this.afterJson = afterJson
        this.reason = reason
        this.traceId = traceId
        this.occurredAt = occurredAt
    }
}
