package com.example.wallet.domain.wallet;

import com.example.wallet.domain.shared.Money;
import com.example.wallet.domain.shared.Reference;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);
    private static final Reference REF = Reference.adjustment("test");

    @Test
    void open_startsAtZeroBalance() {
        Wallet w = Wallet.open("alice", KRW, CLOCK);
        assertThat(w.balance()).isEqualTo(Money.zero(KRW));
        assertThat(w.blocked()).isEqualTo(Money.zero(KRW));
        assertThat(w.available()).isEqualTo(Money.zero(KRW));
    }

    @Test
    void deposit_increasesBalanceAndEmitsEvent() {
        Wallet w = Wallet.open("alice", KRW, CLOCK);
        WalletEvents.WalletDeposited evt = w.deposit(Money.of(1000, KRW), REF, CLOCK);

        assertThat(w.balance()).isEqualTo(Money.of(1000, KRW));
        assertThat(evt.amount()).isEqualTo(Money.of(1000, KRW));
        assertThat(evt.balanceAfter()).isEqualTo(Money.of(1000, KRW));
    }

    @Test
    void deposit_negativeAmount_throws() {
        Wallet w = Wallet.open("alice", KRW, CLOCK);
        assertThatThrownBy(() -> w.deposit(Money.of(-100, KRW), REF, CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    void deposit_differentCurrency_throws() {
        Wallet w = Wallet.open("alice", KRW, CLOCK);
        assertThatThrownBy(() -> w.deposit(Money.of(1, Currency.getInstance("USD")), REF, CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency mismatch");
    }

    @Test
    void withdraw_decreasesBalance() {
        Wallet w = Wallet.open("alice", KRW, CLOCK);
        w.deposit(Money.of(1000, KRW), REF, CLOCK);
        w.withdraw(Money.of(300, KRW), REF, CLOCK);

        assertThat(w.balance()).isEqualTo(Money.of(700, KRW));
    }

    @Test
    void withdraw_insufficientBalance_throws() {
        Wallet w = Wallet.open("alice", KRW, CLOCK);
        w.deposit(Money.of(100, KRW), REF, CLOCK);

        assertThatThrownBy(() -> w.withdraw(Money.of(200, KRW), REF, CLOCK))
                .isInstanceOf(InsufficientBalanceException.class);
    }

    @Test
    void block_reducesAvailableButNotBalance() {
        Wallet w = Wallet.open("alice", KRW, CLOCK);
        w.deposit(Money.of(1000, KRW), REF, CLOCK);
        w.block(Money.of(300, KRW), REF, CLOCK);

        assertThat(w.balance()).isEqualTo(Money.of(1000, KRW));
        assertThat(w.blocked()).isEqualTo(Money.of(300, KRW));
        assertThat(w.available()).isEqualTo(Money.of(700, KRW));
    }

    @Test
    void withdraw_cannotExceedAvailable_evenIfBalanceEnough() {
        Wallet w = Wallet.open("alice", KRW, CLOCK);
        w.deposit(Money.of(1000, KRW), REF, CLOCK);
        w.block(Money.of(800, KRW), REF, CLOCK);   // available=200

        assertThatThrownBy(() -> w.withdraw(Money.of(300, KRW), REF, CLOCK))
                .isInstanceOf(InsufficientBalanceException.class);
    }

    @Test
    void unblock_releasesBlocked() {
        Wallet w = Wallet.open("alice", KRW, CLOCK);
        w.deposit(Money.of(1000, KRW), REF, CLOCK);
        w.block(Money.of(300, KRW), REF, CLOCK);
        w.unblock(Money.of(100, KRW), REF, CLOCK);

        assertThat(w.blocked()).isEqualTo(Money.of(200, KRW));
    }

    @Test
    void unblock_moreThanBlocked_throws() {
        Wallet w = Wallet.open("alice", KRW, CLOCK);
        w.deposit(Money.of(1000, KRW), REF, CLOCK);
        w.block(Money.of(100, KRW), REF, CLOCK);

        assertThatThrownBy(() -> w.unblock(Money.of(200, KRW), REF, CLOCK))
                .isInstanceOf(IllegalStateException.class);
    }
}
