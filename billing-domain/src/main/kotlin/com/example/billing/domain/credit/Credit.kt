package com.example.billing.domain.credit

import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.shared.Money
import com.example.billing.domain.shared.Reference
import java.time.Clock
import java.time.Instant
import java.util.Currency

/**
 * Credit 애그리거트 — 청구서 결제 직전에 적용되는 선불 / 프로모성 잔액.
 *
 * **Wallet 과 무엇이 다른가** (별도 도메인으로 둔 이유):
 * - [com.example.billing.domain.wallet.Wallet] 은 거래 잔액 (입금 / 출금 / 블록). 사용자가
 *   충전한 돈이라 환불 대상.
 * - [Credit] 은 발급된 잔액 (PROMO 쿠폰 / 보상 / 프로모션 등). 환불 불가, 만료 가능, 청구서
 *   결제 시 자동 차감.
 *
 * 회계상 두 잔액의 수익 인식 (revenue recognition) 시점이 다름 — Wallet 은 충전 시점에 부채로
 * 잡고 사용 시점에 수익 전환, Credit 은 발급 시점에 마케팅 비용 / 보상 비용으로 잡힘. 같은
 * 테이블로 합치면 회계 기간말 마감에서 분리 비용이 더 큼.
 *
 * **Invariant**:
 * - `0 <= balance <= grantedAmount` — 잔액은 음수가 될 수 없고 발급액을 초과할 수도 없음
 * - 차감 가능 조건: `status == ACTIVE` && `balance > 0`
 * - `validUntil != null && now >= validUntil` 이면 차감 거절 (status 가 EXPIRED 로 자동 갱신
 *   되지 않은 시점에 차감 호출이 들어와도 도메인이 거절). 만료 처리는 batch 가 명시적으로 호출
 *   — ADR-0019 참조.
 * - 모든 amount 는 이 Credit 의 [currency] 와 동일 (다른 통화는 호출자가 skip).
 *
 * **동시성**: [version] 으로 낙관적 락. 같은 Credit 을 동시에 차감하려 하거나 (동시에 여러
 * invoice 적용), 차감과 만료 batch 가 동시에 들어오는 경우 한쪽이 OptimisticLockException →
 * application service 가 짧은 budget 안에서 재시도
 * ([com.example.billing.application.service.OptimisticLockRetry]).
 *
 * **이벤트 발행 패턴**: 모든 상태 변경 메서드는 [CreditEvents] 의 record 를 반환. 도메인이 직접
 * 발행하지 않고 application service 가 받은 이벤트를 Outbox 에 INSERT — 도메인이 인프라
 * (DB / Kafka) 를 모르게 하기 위한 분리.
 *
 * record-style accessor (id(), customerId(), status() 등) 는 `@get:JvmName` 으로 Java/Kotlin
 * 양쪽 호출자 호환 유지.
 */
class Credit private constructor(
    @get:JvmName("id") val id: CreditId,
    @get:JvmName("customerId") val customerId: CustomerId,
    @get:JvmName("type") val type: CreditType,
    @get:JvmName("currency") val currency: Currency,
    @get:JvmName("grantedAmount") val grantedAmount: Money,
    balance: Money,
    @get:JvmName("validFrom") val validFrom: Instant,
    /** null 이면 만료 없음 (PREPAID 일부 케이스) */
    @get:JvmName("validUntil") val validUntil: Instant?,
    status: CreditStatus,
    @get:JvmName("reason") val reason: String?,
    @get:JvmName("createdAt") val createdAt: Instant,
    updatedAt: Instant,
    @get:JvmName("version") val version: Long,
) {

    @get:JvmName("balance")
    var balance: Money = balance
        private set

    @get:JvmName("status")
    var status: CreditStatus = status
        private set

    @get:JvmName("updatedAt")
    var updatedAt: Instant = updatedAt
        private set

    init {
        checkInvariants()
    }

    /**
     * 잔액 차감. 잔액 부족 / 만료 / 비활성 상태면 예외 발생.
     * 차감 후 잔액이 0 이 되면 status 가 EXHAUSTED 로 자동 전이.
     *
     * @return 항상 [CreditEvents.CreditConsumed]. EXHAUSTED 로 전이됐는지 여부는 [status] 를
     *         직접 확인.
     */
    fun consume(amount: Money, reference: Reference, clock: Clock): CreditEvents.CreditConsumed {
        ensureSameCurrency(amount)
        ensurePositive(amount)
        check(status == CreditStatus.ACTIVE) { "credit not active: status=$status id=$id" }
        val now = clock.instant()
        check(!validFrom.isAfter(now)) { "credit not yet valid: validFrom=$validFrom" }
        check(validUntil == null || now.isBefore(validUntil)) {
            "credit already expired: validUntil=$validUntil"
        }
        if (balance.compareTo(amount) < 0) {
            throw InsufficientCreditException(id, amount, balance)
        }
        this.balance = balance.subtract(amount)
        this.updatedAt = now
        if (balance.isZero) {
            this.status = CreditStatus.EXHAUSTED
        }
        checkInvariants()
        return CreditEvents.CreditConsumed(id, customerId, amount, balance, reference, now)
    }

    /**
     * 만료 처리 (batch 가 호출). 이미 종착 상태면 아무것도 하지 않고 null 반환.
     */
    fun expire(clock: Clock): CreditEvents.CreditExpired? {
        if (status != CreditStatus.ACTIVE) return null
        checkNotNull(validUntil) { "non-expiring credit cannot be expired: id=$id" }
        val now = clock.instant()
        check(!now.isBefore(validUntil)) {
            "validUntil not reached yet: validUntil=$validUntil now=$now"
        }
        val forfeited = balance
        this.balance = Money.zero(currency)
        this.status = CreditStatus.EXPIRED
        this.updatedAt = now
        checkInvariants()
        return CreditEvents.CreditExpired(id, customerId, forfeited, now)
    }

    /**
     * 운영자 강제 회수 (사기/오류 정정 등). 잔액 회수.
     */
    fun revoke(reason: String, clock: Clock): CreditEvents.CreditRevoked {
        check(status == CreditStatus.ACTIVE) {
            "only ACTIVE credit can be revoked: status=$status"
        }
        val revoked = balance
        this.balance = Money.zero(currency)
        this.status = CreditStatus.REVOKED
        this.updatedAt = clock.instant()
        checkInvariants()
        return CreditEvents.CreditRevoked(id, customerId, revoked, reason, updatedAt)
    }

    /** 현재 시점에 차감에 사용 가능한지. */
    fun isUsableAt(now: Instant): Boolean {
        if (status != CreditStatus.ACTIVE) return false
        if (validFrom.isAfter(now)) return false
        if (validUntil != null && !now.isBefore(validUntil)) return false
        return balance.isPositive
    }

    private fun ensureSameCurrency(amount: Money) {
        require(amount.currency() == currency) {
            "currency mismatch: credit=$currency amount=${amount.currency()}"
        }
    }

    private fun checkInvariants() {
        check(!balance.isNegative) { "invariant violation: balance < 0 ($balance)" }
        check(balance.compareTo(grantedAmount) <= 0) {
            "invariant violation: balance > grantedAmount (balance=$balance granted=$grantedAmount)"
        }
    }

    companion object {

        private fun ensurePositive(amount: Money) {
            require(amount.isPositive) { "amount must be positive: $amount" }
        }

        /**
         * 신규 발급. `validUntil = null` 이면 만료 없음.
         */
        @JvmStatic
        fun grant(
            customerId: CustomerId,
            type: CreditType,
            amount: Money,
            validFrom: Instant,
            validUntil: Instant?,
            reason: String?,
            clock: Clock,
        ): Credit {
            require(amount.isPositive) { "granted amount must be positive: $amount" }
            require(validUntil == null || validUntil.isAfter(validFrom)) {
                "validUntil must be after validFrom"
            }
            val now = clock.instant()
            return Credit(
                id = CreditId.newId(),
                customerId = customerId,
                type = type,
                currency = amount.currency(),
                grantedAmount = amount,
                balance = amount,
                validFrom = validFrom,
                validUntil = validUntil,
                status = CreditStatus.ACTIVE,
                reason = reason,
                createdAt = now,
                updatedAt = now,
                version = 0L,
            )
        }

        /** 영속 계층 (DB) 에서 읽어와 도메인 객체로 복원할 때만 호출 — 일반 코드는 [grant] 사용. */
        @JvmStatic
        fun restore(
            id: CreditId,
            customerId: CustomerId,
            type: CreditType,
            currency: Currency,
            grantedAmount: Money,
            balance: Money,
            validFrom: Instant,
            validUntil: Instant?,
            status: CreditStatus,
            reason: String?,
            createdAt: Instant,
            updatedAt: Instant,
            version: Long,
        ): Credit = Credit(
            id = id,
            customerId = customerId,
            type = type,
            currency = currency,
            grantedAmount = grantedAmount,
            balance = balance,
            validFrom = validFrom,
            validUntil = validUntil,
            status = status,
            reason = reason,
            createdAt = createdAt,
            updatedAt = updatedAt,
            version = version,
        )
    }
}
