package com.example.wallet.application.port.in;

import com.example.wallet.application.command.RefundCommand;
import com.example.wallet.domain.refund.Refund;

public interface RefundUseCase {
    Refund refund(RefundCommand command);
}
