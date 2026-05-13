package com.example.billing.domain.credit

import java.util.UUID

/**
 * 크레딧 식별자.
 *
 * Java 호환을 위해 value class 가 아닌 일반 data class. value class 로 가면 Java 호출자에서
 * 매개변수 mangling 이 발생 — `findById(CreditId id)` 등 interface signature 가 깨진다.
 * `@get:JvmName("value")` 로 기존 Java record accessor `value()` 를 그대로 보존.
 */
data class CreditId(@get:JvmName("value") val value: UUID) {

    override fun toString(): String = value.toString()

    companion object {
        @JvmStatic
        fun newId(): CreditId = CreditId(UUID.randomUUID())

        @JvmStatic
        fun of(s: String): CreditId = CreditId(UUID.fromString(s))
    }
}
