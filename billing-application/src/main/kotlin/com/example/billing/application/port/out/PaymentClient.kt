package com.example.billing.application.port.out

import com.example.billing.domain.invoice.Invoice

/**
 * 청구서 결제 시도 (외부 PG 호출). 운영에선 Resilience4j circuit breaker 가 적용된 구현이 들어간다.
 */
interface PaymentClient {

    /**
     * @return true = 결제 성공, false = PG 거절 또는 일시적 실패 (재시도 가능)
     * @throws PaymentFatalException 영구 실패 (카드 만료 등)
     */
    fun charge(invoice: Invoice): Boolean

    class PaymentFatalException(message: String) : RuntimeException(message)
}
