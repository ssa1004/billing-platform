package com.example.billing.domain.order;

import java.util.Objects;
import java.util.UUID;

public record OrderId(UUID value) {
    public OrderId { Objects.requireNonNull(value); }
    public static OrderId newId() { return new OrderId(UUID.randomUUID()); }
    public static OrderId of(String s) { return new OrderId(UUID.fromString(s)); }
    @Override public String toString() { return value.toString(); }
}
