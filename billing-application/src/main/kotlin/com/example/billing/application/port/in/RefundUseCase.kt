package com.example.billing.application.port.`in`

import com.example.billing.application.command.RefundCommand
import com.example.billing.domain.refund.Refund

interface RefundUseCase {
    fun refund(command: RefundCommand): Refund
}
