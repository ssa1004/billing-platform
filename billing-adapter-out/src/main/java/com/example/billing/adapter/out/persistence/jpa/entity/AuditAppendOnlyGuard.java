package com.example.billing.adapter.out.persistence.jpa.entity;

import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;

/**
 * {@link AuditEntryJpaEntity} 의 append-only (한 번 적으면 수정/삭제 안 함, 추가만) 규칙을
 * JPA 레벨에서 강제하는 listener.
 *
 * <p><b>방어선이 여러 겹인 이유</b>: audit 로그는 회계 감사 / 운영 분쟁의 1차 근거라 한 번
 * INSERT 된 row 는 *어떤 경로로도* 수정 / 삭제되면 안 됩니다. 그래서 방어선을 3겹으로 둡니다:
 * <ul>
 *   <li><b>도메인</b> — {@link com.example.billing.domain.audit.AuditEntry} 가 record (불변),
 *       JPA entity 에도 setter 없음. 정상 흐름은 setter 가 없어 자연스럽게 안전.</li>
 *   <li><b>이 listener (JPA)</b> — 누군가 실수 또는 악의로 EntityManager 에 직접 접근해
 *       {@code merge} / {@code remove} 를 호출하는 경로를 차단.</li>
 *   <li><b>DB trigger</b> — V9_1 migration. EntityManager 도 우회하는
 *       {@code createNativeQuery("UPDATE / DELETE ...")} 까지 막는 마지막 방어선.</li>
 * </ul>
 *
 * <p><b>잘못 기록된 entry 는 어떻게 정정하나</b>: 기존 row 를 *수정하지 않고* 정정 내용을 담은
 * *새 row 를 INSERT* 합니다. timeline 에 두 row 가 같이 남는 것이 audit log 의 핵심 — 누가
 * 언제 무엇을 어떻게 정정했는지까지 전부 기록되어야 *진실의 전체 모습* 이 됩니다.</p>
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
