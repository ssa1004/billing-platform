package com.example.billing.domain.refund

import java.util.UUID

/**
 * 환불 식별자.
 *
 * Java 호환을 유지하기 위해 일반 data class 로 둔다. value class 로 가면 Java 호출자에서
 * 매개변수 mangling 이 발생해 `RefundRepository.findById(RefundId id)` 같은 메서드가 호출
 * 불가능해진다 (interface signature 가 깨짐).
 *
 * `@get:JvmName("value")` 로 기존 record accessor `value()` 를 그대로 보존 — RefundJpaMapper
 * 의 `r.id().value()` 호출 등 Java 측 변경 불필요.
 */
data class RefundId(@get:JvmName("value") val value: UUID) {

    override fun toString(): String = value.toString()

    companion object {
        @JvmStatic
        fun newId(): RefundId = RefundId(UUID.randomUUID())

        @JvmStatic
        fun of(s: String): RefundId = RefundId(UUID.fromString(s))
    }
}
