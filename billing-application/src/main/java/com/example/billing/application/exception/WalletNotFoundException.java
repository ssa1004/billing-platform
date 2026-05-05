package com.example.billing.application.exception;

public class WalletNotFoundException extends RuntimeException {
    public WalletNotFoundException(String ownerId) {
        super("wallet not found for owner: " + ownerId);
    }
}
