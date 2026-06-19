package com.example.billing.domain.wallet

import com.example.billing.domain.shared.Money
import com.example.billing.domain.shared.Reference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Currency
import org.junit.jupiter.api.Test

/**
 * [Wallet] invariant 중 기존 Java `WalletTest` 가 비워 둔 부분:
 * - `restore` 의 invariant 재검증 (blocked > balance / 음수 잔액 거부)
 * - block 의 available 부족 가드
 * - zero-amount 거부
 * - 잔액 변경 이벤트 payload (balanceAfter / blockedAfter)
 */
class WalletInvariantTest {

    private val krw: Currency = Currency.getInstance("KRW")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC)
    private val ref: Reference = Reference.adjustment("test")

    @Test
    fun `restore 는 blocked 가 balance 보다 크면 invariant 위반으로 거부`() {
        assertThatThrownBy {
            Wallet.restore(
                WalletId.newId(), "alice", krw,
                Money.of(100L, krw), Money.of(200L, krw),
                clock.instant(), clock.instant(), 1L,
            )
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("blocked > balance")
    }

    @Test
    fun `restore 는 음수 잔액을 거부한다`() {
        assertThatThrownBy {
            Wallet.restore(
                WalletId.newId(), "alice", krw,
                Money.of(-1L, krw), Money.zero(krw),
                clock.instant(), clock.instant(), 1L,
            )
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("balance < 0")
    }

    @Test
    fun `restore 는 정상 상태면 version 까지 그대로 복원한다`() {
        val id = WalletId.newId()
        val w = Wallet.restore(
            id, "alice", krw,
            Money.of(1000L, krw), Money.of(300L, krw),
            clock.instant(), clock.instant(), 7L,
        )

        assertThat(w.id).isEqualTo(id)
        assertThat(w.version).isEqualTo(7L)
        assertThat(w.available()).isEqualTo(Money.of(700L, krw))
    }

    @Test
    fun `block 은 available 을 초과하면 InsufficientBalanceException`() {
        val w = Wallet.open("alice", krw, clock)
        w.deposit(Money.of(500L, krw), ref, clock)

        assertThatThrownBy { w.block(Money.of(600L, krw), ref, clock) }
            .isInstanceOf(InsufficientBalanceException::class.java)
    }

    @Test
    fun `zero 금액 deposit 은 거부된다`() {
        val w = Wallet.open("alice", krw, clock)

        assertThatThrownBy { w.deposit(Money.zero(krw), ref, clock) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be positive")
    }

    @Test
    fun `withdraw 이벤트는 차감 후 잔액을 담는다`() {
        val w = Wallet.open("alice", krw, clock)
        w.deposit(Money.of(1000L, krw), ref, clock)

        val evt = w.withdraw(Money.of(300L, krw), ref, clock)

        assertThat(evt.amount).isEqualTo(Money.of(300L, krw))
        assertThat(evt.balanceAfter).isEqualTo(Money.of(700L, krw))
        assertThat(evt.aggregateId()).isEqualTo(w.id.toString())
    }

    @Test
    fun `block 과 unblock 이벤트는 변경 후 blocked 를 담는다`() {
        val w = Wallet.open("alice", krw, clock)
        w.deposit(Money.of(1000L, krw), ref, clock)

        val blockedEvt = w.block(Money.of(400L, krw), ref, clock)
        assertThat(blockedEvt.blockedAfter).isEqualTo(Money.of(400L, krw))

        val unblockedEvt = w.unblock(Money.of(100L, krw), ref, clock)
        assertThat(unblockedEvt.blockedAfter).isEqualTo(Money.of(300L, krw))
    }
}
