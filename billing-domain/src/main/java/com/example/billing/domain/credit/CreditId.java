package com.example.billing.domain.credit;

import java.util.Objects;
import java.util.UUID;

public record CreditId(UUID value) {
    public CreditId { Objects.requireNonNull(value, "CreditId.value"); }
    public static CreditId newId() { return new CreditId(UUID.randomUUID()); }
    public static CreditId of(String s) { return new CreditId(UUID.fromString(s)); }
    @Override public String toString() { return value.toString(); }
}
