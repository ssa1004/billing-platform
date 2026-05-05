package com.example.wallet.domain.order;

public class IllegalOrderTransitionException extends RuntimeException {
    public IllegalOrderTransitionException(OrderStatus from, OrderStatus to) {
        super("illegal order transition: " + from + " → " + to);
    }
}
