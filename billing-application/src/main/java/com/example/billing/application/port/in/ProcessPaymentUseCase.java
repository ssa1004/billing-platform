package com.example.billing.application.port.in;

import com.example.billing.application.command.ProcessPaymentCommand;
import com.example.billing.domain.payment.Payment;

public interface ProcessPaymentUseCase {
    Payment process(ProcessPaymentCommand command);
}
