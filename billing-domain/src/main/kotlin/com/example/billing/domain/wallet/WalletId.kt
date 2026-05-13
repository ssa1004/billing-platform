package com.example.billing.domain.wallet

import java.util.UUID

/**
 * Wallet 식별자.
 *
 * Java 호환을 위해 value class 가 아닌 일반 data class. `@get:JvmName("value")` 로 기존 Java
 * record accessor `value()` 보존.
 */
data class WalletId(@get:JvmName("value") val value: UUID) {

    override fun toString(): String = value.toString()

    companion object {
        @JvmStatic
        fun newId(): WalletId = WalletId(UUID.randomUUID())

        @JvmStatic
        fun of(s: String): WalletId = WalletId(UUID.fromString(s))
    }
}
