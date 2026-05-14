package com.example.billing.domain.order

/**
 * Order 상태 전이 불가 예외. 도메인이 직접 발행하므로 data class 가 아닌 일반 class.
 */
class IllegalOrderTransitionException(
    from: OrderStatus,
    to: OrderStatus,
) : RuntimeException("illegal order transition: $from → $to")
