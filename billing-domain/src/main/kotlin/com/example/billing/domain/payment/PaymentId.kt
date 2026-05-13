package com.example.billing.domain.payment

import java.util.UUID

/**
 * Payment 식별자.
 *
 * Java 호환을 위해 value class 가 아닌 일반 data class. `@get:JvmName("value")` 로 Java
 * record accessor `value()` 보존.
 */
data class PaymentId(@get:JvmName("value") val value: UUID) {

    override fun toString(): String = value.toString()

    companion object {
        @JvmStatic
        fun newId(): PaymentId = PaymentId(UUID.randomUUID())

        @JvmStatic
        fun of(s: String): PaymentId = PaymentId(UUID.fromString(s))
    }
}
