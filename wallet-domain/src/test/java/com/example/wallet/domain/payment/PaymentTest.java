package com.example.wallet.domain.payment;

import com.example.wallet.domain.order.OrderId;
import com.example.wallet.domain.shared.Money;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void initiate_createsInPendingStatus() {
        Payment p = Payment.initiate(OrderId.newId(), Money.of(1000, KRW), PaymentMethod.CARD, "key-1", CLOCK);

        assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.idempotencyKey()).isEqualTo("key-1");
        assertThat(p.pgTransactionId()).isNull();
        assertThat(p.errorCode()).isNull();
    }

    @Test
    void initiate_negativeAmount_throws() {
        assertThatThrownBy(() ->
                Payment.initiate(OrderId.newId(), Money.of(-100, KRW), PaymentMethod.CARD, "k", CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void initiate_blankIdempotencyKey_throws() {
        assertThatThrownBy(() ->
                Payment.initiate(OrderId.newId(), Money.of(100, KRW), PaymentMethod.CARD, "", CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    void approve_setsApprovedAndPgTxId() {
        Payment p = Payment.initiate(OrderId.newId(), Money.of(1000, KRW), PaymentMethod.CARD, "k", CLOCK);
        var evt = p.approve("pg-tx-1", CLOCK);

        assertThat(p.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(p.pgTransactionId()).isEqualTo("pg-tx-1");
        assertThat(evt.pgTransactionId()).isEqualTo("pg-tx-1");
    }

    @Test
    void approve_twice_throws() {
        Payment p = Payment.initiate(OrderId.newId(), Money.of(1000, KRW), PaymentMethod.CARD, "k", CLOCK);
        p.approve("pg-1", CLOCK);

        assertThatThrownBy(() -> p.approve("pg-2", CLOCK))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reject_capturesError() {
        Payment p = Payment.initiate(OrderId.newId(), Money.of(1000, KRW), PaymentMethod.CARD, "k", CLOCK);
        var evt = p.reject("LIMIT_EXCEEDED", "credit limit", CLOCK);

        assertThat(p.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.errorCode()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(p.errorMessage()).isEqualTo("credit limit");
        assertThat(evt.errorCode()).isEqualTo("LIMIT_EXCEEDED");
    }

    @Test
    void approve_afterReject_throws() {
        Payment p = Payment.initiate(OrderId.newId(), Money.of(1000, KRW), PaymentMethod.CARD, "k", CLOCK);
        p.reject("FAIL", "msg", CLOCK);

        assertThatThrownBy(() -> p.approve("pg-1", CLOCK))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void restore_preservesAllFields() {
        var id = PaymentId.newId();
        var orderId = OrderId.newId();
        Payment p = Payment.restore(id, orderId, Money.of(1000, KRW), PaymentMethod.WALLET, "k",
                PaymentStatus.APPROVED, "pg-x", null, null, CLOCK.instant(), CLOCK.instant(), 3L);

        assertThat(p.id()).isEqualTo(id);
        assertThat(p.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(p.method()).isEqualTo(PaymentMethod.WALLET);
        assertThat(p.version()).isEqualTo(3L);
    }

    @Test
    void terminalStatus_helper() {
        assertThat(PaymentStatus.PENDING.isTerminal()).isFalse();
        assertThat(PaymentStatus.APPROVED.isTerminal()).isTrue();
        assertThat(PaymentStatus.FAILED.isTerminal()).isTrue();
    }
}
