package com.example.wallet.domain.wallet;

import java.util.Objects;
import java.util.UUID;

public record WalletId(UUID value) {
    public WalletId { Objects.requireNonNull(value, "WalletId.value"); }
    public static WalletId newId() { return new WalletId(UUID.randomUUID()); }
    public static WalletId of(String s) { return new WalletId(UUID.fromString(s)); }
    @Override public String toString() { return value.toString(); }
}
