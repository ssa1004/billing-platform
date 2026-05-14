package com.example.billing.domain.order

/**
 * Order 상태머신.
 *
 * ```
 *  CREATED ──► PAID ──► REFUNDED
 *     │
 *     ├──► CANCELLED  (결제 전 취소)
 *     │
 *     └──► FAILED     (결제 시도 실패)
 * ```
 *
 * 천이 가능 여부는 [canTransitionTo] 로 강제. [Order] 가 호출해 invariant 보장.
 */
enum class OrderStatus {
    CREATED,
    PAID,
    REFUNDED,
    CANCELLED,
    FAILED;

    fun isTerminal(): Boolean = this in TERMINAL

    fun canTransitionTo(next: OrderStatus): Boolean = when (this) {
        CREATED -> next == PAID || next == CANCELLED || next == FAILED
        PAID -> next == REFUNDED
        REFUNDED, CANCELLED, FAILED -> false
    }

    private companion object {
        private val TERMINAL = setOf(REFUNDED, CANCELLED, FAILED)
    }
}
