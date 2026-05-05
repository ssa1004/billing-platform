package com.example.billing.application.exception;

import com.example.billing.domain.order.OrderId;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(OrderId id) {
        super("order not found: " + id);
    }
}
