package com.example.billing.domain.invoice;

public class IllegalInvoiceTransitionException extends RuntimeException {
    public IllegalInvoiceTransitionException(InvoiceStatus from, InvoiceStatus to) {
        super("Invoice 상태 전이 불가: " + from + " → " + to);
    }
}
