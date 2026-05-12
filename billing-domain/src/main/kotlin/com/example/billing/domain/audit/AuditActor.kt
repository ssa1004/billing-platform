package com.example.billing.domain.audit

/**
 * 행위 주체 — 사용자 / 운영자 / 시스템 / 외부 통합.
 *
 * **주체 카테고리 (subject type)**:
 * - [Type.USER] — 일반 customer 본인 행위 (자기 invoice 결제 등)
 * - [Type.OPERATOR] — 내부 운영자 행위 (CS 가 크레딧 발급 / 환불 처리 등). 가장 감사 중요.
 * - [Type.SYSTEM] — 자동화 (스케줄러 / 컨슈머 / 만료 batch). subject id 는 job/scheduler 식별자.
 * - [Type.EXTERNAL] — webhook callback / 3rd party API 등 외부 진입점 호출.
 *
 * [ipAddress] / [userAgent] 는 알 수 있을 때만 채움 (HTTP 진입점에선 채우고 SYSTEM 에선 null).
 * 이 둘 만으로도 비정상 접근 패턴 분석 가능.
 *
 * Kotlin `@JvmRecord` 로 컴파일 — Java record 와 동일한 component accessor (`type()`,
 * `id()` 등) 을 노출해 호출자 호환성 (Java + Kotlin) 보존.
 */
@JvmRecord
data class AuditActor(
    val type: Type,
    val id: String,
    val ipAddress: String?,
    val userAgent: String?,
) {
    init {
        require(id.isNotBlank()) { "actor id must not be blank" }
    }

    enum class Type {
        USER,
        OPERATOR,
        SYSTEM,
        EXTERNAL,
    }

    companion object {
        @JvmStatic
        fun user(userId: String, ip: String?, userAgent: String?): AuditActor =
            AuditActor(Type.USER, userId, ip, userAgent)

        @JvmStatic
        fun operator(operatorId: String, ip: String?, userAgent: String?): AuditActor =
            AuditActor(Type.OPERATOR, operatorId, ip, userAgent)

        @JvmStatic
        fun system(component: String): AuditActor =
            AuditActor(Type.SYSTEM, component, null, null)

        @JvmStatic
        fun external(source: String): AuditActor =
            AuditActor(Type.EXTERNAL, source, null, null)
    }
}
