package com.example.billing.domain.shared

/**
 * B2B 고객 식별자. 외부 시스템 (CRM, IAM 등) 과 매핑되므로 String 으로 보관 (UUID 가 아닐 수
 * 있음).
 *
 * Java 호환을 위해 value class 가 아닌 일반 data class. `@get:JvmName("value")` 로 기존 Java
 * record accessor `value()` 를 그대로 보존 — 100+ 호출자 (Java + Kotlin) 무변경.
 */
@ConsistentCopyVisibility
data class CustomerId private constructor(@get:JvmName("value") val value: String) {

    init {
        require(value.isNotBlank()) { "CustomerId must not be blank" }
        require(value.length <= 64) { "CustomerId too long: ${value.length}" }
    }

    override fun toString(): String = value

    companion object {
        @JvmStatic
        fun of(value: String): CustomerId = CustomerId(value)
    }
}
