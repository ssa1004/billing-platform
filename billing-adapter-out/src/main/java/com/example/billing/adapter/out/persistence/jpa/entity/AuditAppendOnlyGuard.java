package com.example.billing.adapter.out.persistence.jpa.entity;

import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;

/**
 * {@link AuditEntryJpaEntity} 의 append-only invariant 를 JPA 레벨에서 강제하는 listener.
 *
 * <p>Audit 로그는 회계 감사 / 운영 분쟁의 1차 근거 자료라 한 번 INSERT 된 이후 *어떤 경로로도*
 * 수정 / 삭제되어선 안 됩니다. 도메인 객체에 setter 가 없어 정상 흐름은 안전하지만, 실수 또는
 * 악의로 EntityManager 에 직접 접근해 {@code merge} / {@code remove} 를 호출하면 막을 수
 * 없습니다. listener 가 그 마지막 길을 막아줍니다.</p>
 *
 * <p>"잘못 기록된 entry" 는 절대 수정하지 말고, *새 정정 entry* 를 INSERT 해서 timeline 자체가
 * 진실의 전체 모습을 그대로 보존하도록 하는 것이 audit log 의 정의입니다.</p>
 */
public class AuditAppendOnlyGuard {

    @PreUpdate
    public void onPreUpdate(Object entity) {
        throw new UnsupportedOperationException(
                "audit entry is append-only — UPDATE is forbidden. " +
                        "정정이 필요하면 새 row 를 INSERT 하세요.");
    }

    @PreRemove
    public void onPreRemove(Object entity) {
        throw new UnsupportedOperationException(
                "audit entry is append-only — DELETE is forbidden.");
    }
}
