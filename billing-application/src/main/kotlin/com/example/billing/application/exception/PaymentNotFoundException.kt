package com.example.billing.application.exception

import com.example.billing.domain.payment.PaymentId

class PaymentNotFoundException(id: PaymentId) : RuntimeException("payment not found: $id")
