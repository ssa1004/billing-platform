package com.example.billing.domain.audit;

import java.util.Objects;

/**
 * 행위 주체 — 사용자 / 운영자 / 시스템 / 외부 통합.
 *
 * <p><b>주체 카테고리 (subject type)</b>:
 * <ul>
 *   <li>{@code USER} — 일반 customer 본인 행위 (자기 invoice 결제 등)</li>
 *   <li>{@code OPERATOR} — 내부 운영자 행위 (CS 가 크레딧 발급 / 환불 처리 등). 가장 감사 중요.</li>
 *   <li>{@code SYSTEM} — 자동화 (스케줄러 / 컨슈머 / 만료 batch). subject id 는 job/scheduler 식별자.</li>
 *   <li>{@code EXTERNAL} — webhook callback / 3rd party API 등 외부 진입점 호출.</li>
 * </ul>
 *
 * <p>{@code ipAddress / userAgent} 는 *알 수 있을 때만* 채움 (HTTP 진입점에선 채우고 SYSTEM
 * 에선 null). 이 둘 만으로도 비정상 접근 패턴 분석 가능.</p>
 */
public record AuditActor(
        Type type,
        String id,
        String ipAddress,    // nullable
        String userAgent     // nullable
) {

    public AuditActor {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("actor id must not be blank");
    }

    public enum Type {
        USER,
        OPERATOR,
        SYSTEM,
        EXTERNAL
    }

    public static AuditActor user(String userId, String ip, String userAgent) {
        return new AuditActor(Type.USER, userId, ip, userAgent);
    }

    public static AuditActor operator(String operatorId, String ip, String userAgent) {
        return new AuditActor(Type.OPERATOR, operatorId, ip, userAgent);
    }

    public static AuditActor system(String component) {
        return new AuditActor(Type.SYSTEM, component, null, null);
    }

    public static AuditActor external(String source) {
        return new AuditActor(Type.EXTERNAL, source, null, null);
    }
}
