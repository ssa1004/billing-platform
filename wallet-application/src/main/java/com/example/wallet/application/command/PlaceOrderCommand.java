package com.example.wallet.application.command;

import com.example.wallet.domain.order.OrderItem;
import com.example.wallet.domain.shared.Money;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;

public record PlaceOrderCommand(
        String idempotencyKey,
        String buyerId,
        Currency currency,
        List<OrderLine> lines
) {
    public record OrderLine(String sku, int quantity, BigDecimal unitPrice) {}

    public List<OrderItem> toOrderItems() {
        return lines.stream()
                .map(l -> new OrderItem(l.sku(), l.quantity(), Money.of(l.unitPrice(), currency)))
                .toList();
    }
}
