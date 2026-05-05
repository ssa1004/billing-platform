package com.example.wallet.domain.order;

import com.example.wallet.domain.shared.DomainEvent;
import com.example.wallet.domain.shared.Money;

import java.time.Instant;

public sealed interface OrderEvents extends DomainEvent
        permits OrderEvents.OrderPlaced,
                OrderEvents.OrderPaid,
                OrderEvents.OrderCancelled,
                OrderEvents.OrderRefunded,
                OrderEvents.OrderFailed {

    record OrderPlaced(OrderId orderId, String buyerId, Money totalAmount, Instant occurredAt) implements OrderEvents {
        @Override public String aggregateId() { return orderId.toString(); }
    }

    record OrderPaid(OrderId orderId, String paymentId, Money totalAmount, Instant occurredAt) implements OrderEvents {
        @Override public String aggregateId() { return orderId.toString(); }
    }

    record OrderCancelled(OrderId orderId, String reason, Instant occurredAt) implements OrderEvents {
        @Override public String aggregateId() { return orderId.toString(); }
    }

    record OrderRefunded(OrderId orderId, String refundId, Money totalAmount, Instant occurredAt) implements OrderEvents {
        @Override public String aggregateId() { return orderId.toString(); }
    }

    record OrderFailed(OrderId orderId, String reason, Instant occurredAt) implements OrderEvents {
        @Override public String aggregateId() { return orderId.toString(); }
    }
}
