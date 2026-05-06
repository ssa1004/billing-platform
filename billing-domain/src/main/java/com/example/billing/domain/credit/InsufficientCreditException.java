package com.example.billing.domain.credit;

import com.example.billing.domain.shared.Money;

public class InsufficientCreditException extends RuntimeException {

    private final CreditId creditId;
    private final Money requested;
    private final Money available;

    public InsufficientCreditException(CreditId creditId, Money requested, Money available) {
        super("insufficient credit: id=" + creditId + " requested=" + requested + " available=" + available);
        this.creditId = creditId;
        this.requested = requested;
        this.available = available;
    }

    public CreditId creditId() { return creditId; }
    public Money requested() { return requested; }
    public Money available() { return available; }
}
