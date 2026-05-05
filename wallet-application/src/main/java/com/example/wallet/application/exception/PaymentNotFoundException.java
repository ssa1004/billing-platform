package com.example.wallet.application.exception;

import com.example.wallet.domain.payment.PaymentId;

public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(PaymentId id) {
        super("payment not found: " + id);
    }
}
