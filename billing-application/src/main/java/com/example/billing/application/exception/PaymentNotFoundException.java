package com.example.billing.application.exception;

import com.example.billing.domain.payment.PaymentId;

public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(PaymentId id) {
        super("payment not found: " + id);
    }
}
