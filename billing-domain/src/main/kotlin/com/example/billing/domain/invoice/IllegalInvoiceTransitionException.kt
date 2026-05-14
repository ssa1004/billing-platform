package com.example.billing.domain.invoice

/**
 * Invoice 상태 전이 불가 예외. 도메인이 직접 발행하므로 data class 가 아닌 일반 class.
 */
class IllegalInvoiceTransitionException(
    from: InvoiceStatus,
    to: InvoiceStatus,
) : RuntimeException("Invoice 상태 전이 불가: $from → $to")
