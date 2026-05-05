package com.example.wallet.domain.order;

import com.example.wallet.domain.shared.Money;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * 주문 라인. immutable. 합산 금액은 {@link Money} 로 통화 정합성 보장.
 */
public record OrderItem(String sku, int quantity, Money unitPrice) {

    public OrderItem {
        Objects.requireNonNull(sku, "sku");
        if (sku.isBlank()) throw new IllegalArgumentException("sku must not be blank");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive: " + quantity);
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (!unitPrice.isPositive()) throw new IllegalArgumentException("unitPrice must be positive: " + unitPrice);
    }

    public static OrderItem of(String sku, int quantity, BigDecimal unitPrice, Currency currency) {
        return new OrderItem(sku, quantity, Money.of(unitPrice, currency));
    }

    public Money lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
