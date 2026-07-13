package com.example.billing.application.exception

import com.example.billing.domain.payment.PaymentId

/**
 * 같은 payment 에 이미 활성(FAILED 아님) 환불이 있는데 또 환불을 요청했을 때.
 * 서로 다른 Idempotency-Key 로 같은 결제를 두 번 환불하는 이중 지급을 막는다.
 */
class RefundAlreadyRequestedException(paymentId: PaymentId) :
    RuntimeException("active refund already exists for payment: $paymentId")
