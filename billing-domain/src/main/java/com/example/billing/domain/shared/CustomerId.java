package com.example.billing.domain.shared;

import java.util.Objects;

/**
 * B2B 고객 식별자. 외부 시스템 (CRM, IAM 등) 과 매핑되므로 String 으로 보관 (UUID 가 아닐 수
 * 있음).
 */
public final class CustomerId {

    private final String value;

    private CustomerId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CustomerId must not be blank");
        }
        if (value.length() > 64) {
            throw new IllegalArgumentException("CustomerId too long: " + value.length());
        }
        this.value = value;
    }

    public static CustomerId of(String value) {
        return new CustomerId(value);
    }

    public String value() { return value; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CustomerId other)) return false;
        return value.equals(other.value);
    }

    @Override public int hashCode() { return Objects.hash(value); }

    @Override public String toString() { return value; }
}
