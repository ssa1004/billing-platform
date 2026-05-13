package com.example.billing.domain.shared

import java.time.Instant

/**
 * 도메인 이벤트 marker interface. 모든 이벤트는 sealed 트리에 속한다 — 외부 시스템과의 이벤트
 * 컨트랙트 안정성 + JSON 직렬화 시 type discrimination 용이.
 *
 * Java 호출자가 `event.aggregateId()` / `event.occurredAt()` 로 호출하므로 메서드 시그니처는
 * Java interface 와 동일하게 유지. Kotlin 측에서는 nested data class 가 `private val
 * occurredAtInstant` 를 두고 `override fun occurredAt()` 로 위임하는 패턴을 사용한다 (data class
 * 의 component name 충돌 회피).
 */
interface DomainEvent {

    fun aggregateId(): String

    fun occurredAt(): Instant
}
