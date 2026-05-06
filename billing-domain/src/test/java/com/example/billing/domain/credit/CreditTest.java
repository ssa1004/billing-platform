package com.example.billing.domain.credit;

import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import com.example.billing.domain.shared.Reference;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreditTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final CustomerId ALICE = CustomerId.of("alice");
    private static final Instant NOW = Instant.parse("2026-05-04T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Reference INVOICE_REF = Reference.adjustment("invoice:test");

    private static Money won(long amount) {
        return Money.of(BigDecimal.valueOf(amount), KRW);
    }

    private static Credit grantWithExpiry(Money amount, Instant validUntil) {
        return Credit.grant(ALICE, CreditType.PROMO, amount, NOW.minusSeconds(60), validUntil, "test", CLOCK);
    }

    @Test
    void grant_initializesActiveWithFullBalance() {
        Credit c = grantWithExpiry(won(10_000), NOW.plusSeconds(86400));
        assertThat(c.status()).isEqualTo(CreditStatus.ACTIVE);
        assertThat(c.balance()).isEqualTo(won(10_000));
        assertThat(c.grantedAmount()).isEqualTo(won(10_000));
        assertThat(c.customerId()).isEqualTo(ALICE);
    }

    @Test
    void grant_rejectsZeroOrNegativeAmount() {
        assertThatThrownBy(() -> grantWithExpiry(won(0), NOW.plusSeconds(86400)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void grant_rejectsValidUntilBeforeValidFrom() {
        assertThatThrownBy(() ->
                Credit.grant(ALICE, CreditType.PROMO, won(10_000),
                        NOW, NOW.minusSeconds(60), "bad", CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validUntil");
    }

    @Test
    void consume_partialDecrementsBalance() {
        Credit c = grantWithExpiry(won(10_000), NOW.plusSeconds(86400));
        var event = c.consume(won(3_000), INVOICE_REF, CLOCK);
        assertThat(c.balance()).isEqualTo(won(7_000));
        assertThat(c.status()).isEqualTo(CreditStatus.ACTIVE);
        assertThat(event.consumedAmount()).isEqualTo(won(3_000));
        assertThat(event.remainingBalance()).isEqualTo(won(7_000));
    }

    @Test
    void consume_exact_transitionsToExhausted() {
        Credit c = grantWithExpiry(won(5_000), NOW.plusSeconds(86400));
        c.consume(won(5_000), INVOICE_REF, CLOCK);
        assertThat(c.status()).isEqualTo(CreditStatus.EXHAUSTED);
        assertThat(c.balance()).isEqualTo(Money.zero(KRW));
    }

    @Test
    void consume_overBalance_throws() {
        Credit c = grantWithExpiry(won(1_000), NOW.plusSeconds(86400));
        assertThatThrownBy(() -> c.consume(won(2_000), INVOICE_REF, CLOCK))
                .isInstanceOf(InsufficientCreditException.class);
        // 잔액 / 상태 변화 없음
        assertThat(c.balance()).isEqualTo(won(1_000));
        assertThat(c.status()).isEqualTo(CreditStatus.ACTIVE);
    }

    @Test
    void consume_afterExpiry_throws() {
        Credit c = grantWithExpiry(won(1_000), NOW.minusSeconds(1));
        assertThatThrownBy(() -> c.consume(won(500), INVOICE_REF, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void consume_beforeValidFrom_throws() {
        Credit c = Credit.grant(ALICE, CreditType.PROMO, won(1_000),
                NOW.plusSeconds(3600), NOW.plusSeconds(86400), "future", CLOCK);
        assertThatThrownBy(() -> c.consume(won(500), INVOICE_REF, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not yet valid");
    }

    @Test
    void expire_movesActiveToExpiredAndForfeitsBalance() {
        Credit c = grantWithExpiry(won(1_000), NOW.minusSeconds(1));
        var event = c.expire(CLOCK);
        assertThat(c.status()).isEqualTo(CreditStatus.EXPIRED);
        assertThat(c.balance()).isEqualTo(Money.zero(KRW));
        assertThat(event.forfeitedBalance()).isEqualTo(won(1_000));
    }

    @Test
    void expire_beforeValidUntil_throws() {
        Credit c = grantWithExpiry(won(1_000), NOW.plusSeconds(86400));
        assertThatThrownBy(() -> c.expire(CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("validUntil not reached");
    }

    @Test
    void expire_alreadyTerminal_returnsNull() {
        Credit c = grantWithExpiry(won(1_000), NOW.plusSeconds(86400));
        c.consume(won(1_000), INVOICE_REF, CLOCK);  // → EXHAUSTED
        assertThat(c.expire(CLOCK)).isNull();
        assertThat(c.status()).isEqualTo(CreditStatus.EXHAUSTED);  // 변하지 않음
    }

    @Test
    void revoke_movesActiveToRevokedAndCapturesBalance() {
        Credit c = grantWithExpiry(won(800), NOW.plusSeconds(86400));
        var event = c.revoke("fraud detected", CLOCK);
        assertThat(c.status()).isEqualTo(CreditStatus.REVOKED);
        assertThat(c.balance()).isEqualTo(Money.zero(KRW));
        assertThat(event.revokedBalance()).isEqualTo(won(800));
        assertThat(event.reason()).isEqualTo("fraud detected");
    }

    @Test
    void isUsableAt_reflectsLifecycleAndValidity() {
        Credit active = grantWithExpiry(won(100), NOW.plusSeconds(60));
        assertThat(active.isUsableAt(NOW)).isTrue();
        assertThat(active.isUsableAt(NOW.plusSeconds(120))).isFalse();   // 만료 후

        Credit exhausted = grantWithExpiry(won(100), NOW.plusSeconds(60));
        exhausted.consume(won(100), INVOICE_REF, CLOCK);
        assertThat(exhausted.isUsableAt(NOW)).isFalse();
    }
}
