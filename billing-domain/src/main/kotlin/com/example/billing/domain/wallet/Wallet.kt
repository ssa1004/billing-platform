package com.example.billing.domain.wallet

import com.example.billing.domain.shared.Money
import com.example.billing.domain.shared.Reference
import java.time.Clock
import java.time.Instant
import java.util.Currency

/**
 * Wallet 애그리거트 루트 (한 트랜잭션으로 같이 저장되는 도메인 객체 묶음의 진입점, DDD 용어).
 *
 * **도메인 invariant (이 객체가 어떤 시점에도 항상 만족해야 하는 규칙)**:
 * - `balance >= 0` (음수 잔액 금지)
 * - `blocked >= 0` (블록 금액 음수 금지)
 * - `blocked <= balance` (보류된 금액은 잔액 안에 들어 있어야 함 — 잔액보다 더 많이 블록할
 *   수는 없다는 뜻)
 * - 모든 amount 는 wallet.currency 와 동일 (KRW Wallet 에 USD 출금 불가)
 *
 * 이 규칙들은 도메인 메서드 (deposit / withdraw / block / unblock) 안에서 강제되고, 외부에서
 * 직접 필드를 바꾸는 경로는 없습니다 (private set 만 노출).
 *
 * **동시성**: [version] 필드로 낙관적 락. 같은 wallet 을 동시에 두 트랜잭션이 수정하려고 하면
 * 늦은 쪽은 OptimisticLockException → application service 가 짧은 budget 안에서 재시도
 * (`OptimisticLockRetry`). 충돌이 자주 일어나는 핫 wallet 은 Postgres advisory lock 으로 직렬화
 * 보강 가능 (ADR-0007).
 *
 * **이벤트 발행 패턴**: 모든 잔액 변경 메서드는 변경 결과를 표현하는 [WalletEvents] 의 record
 * 를 반환 만 합니다. 발행 자체는 도메인이 안 함 — 호출자 (application service) 가 받은 이벤트
 * 를 Outbox 에 INSERT (Kafka 발행 + Ledger 기록은 그 뒤 단계). 도메인이 인프라 (DB / 메시지
 * 브로커) 를 모르게 하기 위한 분리.
 *
 * record-style accessor (`id()` / `balance()` / `version()` 등) 는 `@get:JvmName` 으로
 * Java/Kotlin 양쪽 호출자 호환 유지.
 */
class Wallet private constructor(
    @get:JvmName("id") val id: WalletId,
    @get:JvmName("ownerId") val ownerId: String,
    @get:JvmName("currency") val currency: Currency,
    balance: Money,
    blocked: Money,
    @get:JvmName("createdAt") val createdAt: Instant,
    updatedAt: Instant,
    @get:JvmName("version") val version: Long,
) {

    @get:JvmName("balance")
    var balance: Money = balance
        private set

    @get:JvmName("blocked")
    var blocked: Money = blocked
        private set

    @get:JvmName("updatedAt")
    var updatedAt: Instant = updatedAt
        private set

    fun deposit(amount: Money, reference: Reference, clock: Clock): WalletEvents.WalletDeposited {
        ensureSameCurrency(amount)
        ensurePositive(amount)
        this.balance = balance.add(amount)
        this.updatedAt = clock.instant()
        checkInvariants()
        return WalletEvents.WalletDeposited(id, amount, balance, reference, updatedAt)
    }

    fun withdraw(amount: Money, reference: Reference, clock: Clock): WalletEvents.WalletWithdrawn {
        ensureSameCurrency(amount)
        ensurePositive(amount)
        val available = balance.subtract(blocked)
        if (available.compareTo(amount) < 0) {
            throw InsufficientBalanceException(id, amount, available)
        }
        this.balance = balance.subtract(amount)
        this.updatedAt = clock.instant()
        checkInvariants()
        return WalletEvents.WalletWithdrawn(id, amount, balance, reference, updatedAt)
    }

    fun block(amount: Money, reference: Reference, clock: Clock): WalletEvents.WalletBlocked {
        ensureSameCurrency(amount)
        ensurePositive(amount)
        val available = balance.subtract(blocked)
        if (available.compareTo(amount) < 0) {
            throw InsufficientBalanceException(id, amount, available)
        }
        this.blocked = blocked.add(amount)
        this.updatedAt = clock.instant()
        checkInvariants()
        return WalletEvents.WalletBlocked(id, amount, blocked, reference, updatedAt)
    }

    fun unblock(amount: Money, reference: Reference, clock: Clock): WalletEvents.WalletUnblocked {
        ensureSameCurrency(amount)
        ensurePositive(amount)
        check(blocked.compareTo(amount) >= 0) {
            "cannot unblock more than blocked: blocked=$blocked requested=$amount"
        }
        this.blocked = blocked.subtract(amount)
        this.updatedAt = clock.instant()
        checkInvariants()
        return WalletEvents.WalletUnblocked(id, amount, blocked, reference, updatedAt)
    }

    fun available(): Money = balance.subtract(blocked)

    private fun ensureSameCurrency(amount: Money) {
        require(amount.currency == this.currency) {
            "currency mismatch: wallet=$currency amount=${amount.currency}"
        }
    }

    private fun checkInvariants() {
        check(!balance.isNegative) { "invariant violation: balance < 0 ($balance)" }
        check(!blocked.isNegative) { "invariant violation: blocked < 0 ($blocked)" }
        check(blocked.compareTo(balance) <= 0) {
            "invariant violation: blocked > balance (blocked=$blocked balance=$balance)"
        }
    }

    companion object {

        private fun ensurePositive(amount: Money) {
            require(amount.isPositive) { "amount must be positive: $amount" }
        }

        /** 신규 Wallet 개설 (잔액 0). */
        @JvmStatic
        fun open(ownerId: String, currency: Currency, clock: Clock): Wallet {
            val now = clock.instant()
            return Wallet(
                id = WalletId.newId(),
                ownerId = ownerId,
                currency = currency,
                balance = Money.zero(currency),
                blocked = Money.zero(currency),
                createdAt = now,
                updatedAt = now,
                version = 0L,
            )
        }

        /** 영속 계층 (DB 등) 에서 읽어와 도메인 객체로 복원 — 외부에서만 호출. */
        @JvmStatic
        fun restore(
            id: WalletId,
            ownerId: String,
            currency: Currency,
            balance: Money,
            blocked: Money,
            createdAt: Instant,
            updatedAt: Instant,
            version: Long,
        ): Wallet {
            val w = Wallet(id, ownerId, currency, balance, blocked, createdAt, updatedAt, version)
            w.checkInvariants()
            return w
        }
    }
}
