package com.example.wallet.application.port.in;

import com.example.wallet.application.command.ProcessPaymentCommand;
import com.example.wallet.domain.payment.Payment;

public interface ProcessPaymentUseCase {
    Payment process(ProcessPaymentCommand command);
}
