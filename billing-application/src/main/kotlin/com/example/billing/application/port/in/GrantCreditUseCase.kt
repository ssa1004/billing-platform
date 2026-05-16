package com.example.billing.application.port.`in`

import com.example.billing.application.command.GrantCreditCommand
import com.example.billing.domain.credit.Credit

interface GrantCreditUseCase {
    fun grant(command: GrantCreditCommand): Credit
}
