package com.example.billing.application.exception;

import com.example.billing.domain.refund.RefundId;

public class RefundNotFoundException extends RuntimeException {
    public RefundNotFoundException(RefundId id) {
        super("refund not found: " + id);
    }
}
