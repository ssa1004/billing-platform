package com.example.billing.domain.order;

public class IllegalOrderTransitionException extends RuntimeException {
    public IllegalOrderTransitionException(OrderStatus from, OrderStatus to) {
        super("illegal order transition: " + from + " → " + to);
    }
}
