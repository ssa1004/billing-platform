package com.example.billing.application.exception;

import java.util.UUID;

public class InvoiceNotFoundException extends RuntimeException {
    public InvoiceNotFoundException(UUID id) {
        super("invoice not found: " + id);
    }
}
