package com.example.billing.application.port.in;

import com.example.billing.application.command.RefundCommand;
import com.example.billing.domain.refund.Refund;

public interface RefundUseCase {
    Refund refund(RefundCommand command);
}
