package com.example.billing.domain.payment;

import java.util.Objects;
import java.util.UUID;

public record PaymentId(UUID value) {
    public PaymentId { Objects.requireNonNull(value); }
    public static PaymentId newId() { return new PaymentId(UUID.randomUUID()); }
    public static PaymentId of(String s) { return new PaymentId(UUID.fromString(s)); }
    @Override public String toString() { return value.toString(); }
}
