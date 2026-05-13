package com.example.billing.domain.wallet

import com.example.billing.domain.shared.DomainEvent
import com.example.billing.domain.shared.Money
import com.example.billing.domain.shared.Reference
import java.time.Instant

/**
 * Wallet 도메인 이벤트 sealed 트리.
 *
 * 모든 잔액 변경 메서드는 [Wallet] 가 직접 상태를 바꾸고 해당 이벤트를 반환한다. 호출 측
 * (application service) 가 Outbox 에 기록 → Kafka publish.
 *
 * Java 호출자 (`OutboxEventPublisher`, `RefundService` 등) 는 `event.aggregateId()` /
 * `event.amount()` / `event.balanceAfter()` 등 record-style accessor 그대로 호출. data class
 * 의 component 이름 충돌 회피를 위해 `private val occurredAtInstant` + `override fun
 * occurredAt() = it` 패턴 사용 (credit / refund 와 동일).
 */
sealed interface WalletEvents : DomainEvent {

    data class WalletDeposited(
        @get:JvmName("walletId") val walletId: WalletId,
        @get:JvmName("amount") val amount: Money,
        @get:JvmName("balanceAfter") val balanceAfter: Money,
        @get:JvmName("reference") val reference: Reference,
        private val occurredAtInstant: Instant,
    ) : WalletEvents {
        override fun aggregateId(): String = walletId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class WalletWithdrawn(
        @get:JvmName("walletId") val walletId: WalletId,
        @get:JvmName("amount") val amount: Money,
        @get:JvmName("balanceAfter") val balanceAfter: Money,
        @get:JvmName("reference") val reference: Reference,
        private val occurredAtInstant: Instant,
    ) : WalletEvents {
        override fun aggregateId(): String = walletId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class WalletBlocked(
        @get:JvmName("walletId") val walletId: WalletId,
        @get:JvmName("amount") val amount: Money,
        @get:JvmName("blockedAfter") val blockedAfter: Money,
        @get:JvmName("reference") val reference: Reference,
        private val occurredAtInstant: Instant,
    ) : WalletEvents {
        override fun aggregateId(): String = walletId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class WalletUnblocked(
        @get:JvmName("walletId") val walletId: WalletId,
        @get:JvmName("amount") val amount: Money,
        @get:JvmName("blockedAfter") val blockedAfter: Money,
        @get:JvmName("reference") val reference: Reference,
        private val occurredAtInstant: Instant,
    ) : WalletEvents {
        override fun aggregateId(): String = walletId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }
}
