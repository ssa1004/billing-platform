package com.example.wallet.domain.payment;

import com.example.wallet.domain.order.OrderId;
import com.example.wallet.domain.shared.DomainEvent;
import com.example.wallet.domain.shared.Money;

import java.time.Instant;

public sealed interface PaymentEvents extends DomainEvent
        permits PaymentEvents.PaymentApproved, PaymentEvents.PaymentRejected {

    record PaymentApproved(PaymentId paymentId, OrderId orderId, Money amount,
                           String pgTransactionId, Instant occurredAt) implements PaymentEvents {
        @Override public String aggregateId() { return paymentId.toString(); }
    }

    record PaymentRejected(PaymentId paymentId, OrderId orderId, Money amount,
                           String errorCode, String errorMessage, Instant occurredAt) implements PaymentEvents {
        @Override public String aggregateId() { return paymentId.toString(); }
    }
}
