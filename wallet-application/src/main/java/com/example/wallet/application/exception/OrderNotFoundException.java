package com.example.wallet.application.exception;

import com.example.wallet.domain.order.OrderId;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(OrderId id) {
        super("order not found: " + id);
    }
}
