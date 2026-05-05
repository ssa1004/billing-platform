package com.example.billing.domain.refund;

import com.example.billing.domain.payment.PaymentId;
import com.example.billing.domain.shared.Money;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void request_createsInRequestedStatus() {
        Refund r = Refund.request(PaymentId.newId(), Money.of(1000, KRW), "user request", CLOCK);

        assertThat(r.status()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(r.amount()).isEqualTo(Money.of(1000, KRW));
        assertThat(r.completedAt()).isNull();
    }

    @Test
    void request_negativeAmount_throws() {
        assertThatThrownBy(() ->
                Refund.request(PaymentId.newId(), Money.of(-100, KRW), "x", CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approve_then_complete_flow() {
        Refund r = Refund.request(PaymentId.newId(), Money.of(1000, KRW), "x", CLOCK);
        r.approve("pg-refund-1", CLOCK);
        assertThat(r.status()).isEqualTo(RefundStatus.APPROVED);
        assertThat(r.pgRefundId()).isEqualTo("pg-refund-1");

        r.complete(CLOCK);
        assertThat(r.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(r.completedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void complete_beforeApprove_throws() {
        Refund r = Refund.request(PaymentId.newId(), Money.of(1000, KRW), "x", CLOCK);

        assertThatThrownBy(() -> r.complete(CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APPROVED");
    }

    @Test
    void approve_twice_throws() {
        Refund r = Refund.request(PaymentId.newId(), Money.of(1000, KRW), "x", CLOCK);
        r.approve("pg-1", CLOCK);

        assertThatThrownBy(() -> r.approve("pg-2", CLOCK))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fail_canBeCalledFromAnyNonTerminalStatus() {
        Refund r1 = Refund.request(PaymentId.newId(), Money.of(1000, KRW), "x", CLOCK);
        r1.fail("PG declined", CLOCK);
        assertThat(r1.status()).isEqualTo(RefundStatus.FAILED);

        Refund r2 = Refund.request(PaymentId.newId(), Money.of(1000, KRW), "x", CLOCK);
        r2.approve("pg-1", CLOCK);
        r2.fail("post-approve failure", CLOCK);
        assertThat(r2.status()).isEqualTo(RefundStatus.FAILED);
    }

    @Test
    void fail_afterTerminal_throws() {
        Refund r = Refund.request(PaymentId.newId(), Money.of(1000, KRW), "x", CLOCK);
        r.approve("pg-1", CLOCK);
        r.complete(CLOCK);

        assertThatThrownBy(() -> r.fail("oops", CLOCK))
                .isInstanceOf(IllegalStateException.class);
    }
}
