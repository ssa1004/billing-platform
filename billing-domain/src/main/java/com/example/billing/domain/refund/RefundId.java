package com.example.billing.domain.refund;

import java.util.Objects;
import java.util.UUID;

public record RefundId(UUID value) {
    public RefundId { Objects.requireNonNull(value); }
    public static RefundId newId() { return new RefundId(UUID.randomUUID()); }
    public static RefundId of(String s) { return new RefundId(UUID.fromString(s)); }
    @Override public String toString() { return value.toString(); }
}
