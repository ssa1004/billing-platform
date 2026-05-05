package com.example.wallet.domain.order;

import com.example.wallet.domain.shared.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    private List<OrderItem> twoItems() {
        return List.of(
                OrderItem.of("SKU-1", 2, BigDecimal.valueOf(1000), KRW),
                OrderItem.of("SKU-2", 1, BigDecimal.valueOf(500), KRW)
        );
    }

    @Test
    void place_calculatesTotalAndCreatesInCreatedStatus() {
        Order o = Order.place("alice", twoItems(), CLOCK);

        assertThat(o.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(o.totalAmount()).isEqualTo(Money.of(2500, KRW));
        assertThat(o.items()).hasSize(2);
    }

    @Test
    void place_emptyItems_throws() {
        assertThatThrownBy(() -> Order.place("alice", List.of(), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void place_mixedCurrencies_throws() {
        var items = List.of(
                OrderItem.of("SKU-1", 1, BigDecimal.valueOf(1000), KRW),
                OrderItem.of("SKU-2", 1, BigDecimal.valueOf(1), Currency.getInstance("USD"))
        );
        assertThatThrownBy(() -> Order.place("alice", items, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPaid_fromCreated_works() {
        Order o = Order.place("alice", twoItems(), CLOCK);
        var evt = o.markPaid("payment-1", CLOCK);

        assertThat(o.status()).isEqualTo(OrderStatus.PAID);
        assertThat(o.paymentId()).isEqualTo("payment-1");
        assertThat(evt.paymentId()).isEqualTo("payment-1");
    }

    @Test
    void markPaid_twice_throws() {
        Order o = Order.place("alice", twoItems(), CLOCK);
        o.markPaid("payment-1", CLOCK);

        assertThatThrownBy(() -> o.markPaid("payment-2", CLOCK))
                .isInstanceOf(IllegalOrderTransitionException.class);
    }

    @Test
    void cancel_afterPaid_throws() {
        Order o = Order.place("alice", twoItems(), CLOCK);
        o.markPaid("payment-1", CLOCK);

        assertThatThrownBy(() -> o.cancel("changed mind", CLOCK))
                .isInstanceOf(IllegalOrderTransitionException.class);
    }

    @Test
    void refund_fromPaid_works() {
        Order o = Order.place("alice", twoItems(), CLOCK);
        o.markPaid("payment-1", CLOCK);
        o.markRefunded("refund-1", CLOCK);

        assertThat(o.status()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(o.refundId()).isEqualTo("refund-1");
    }

    @Test
    void refund_fromCreated_throws() {
        Order o = Order.place("alice", twoItems(), CLOCK);

        assertThatThrownBy(() -> o.markRefunded("refund-1", CLOCK))
                .isInstanceOf(IllegalOrderTransitionException.class);
    }

    @Test
    void item_negativeQuantity_throws() {
        assertThatThrownBy(() -> OrderItem.of("SKU-1", -1, BigDecimal.TEN, KRW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
