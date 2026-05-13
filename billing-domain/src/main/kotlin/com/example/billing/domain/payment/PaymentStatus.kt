package com.example.billing.domain.payment

enum class PaymentStatus {
    /** PG 호출 전 */
    PENDING,

    /** PG 응답 OK */
    APPROVED,

    /** PG 응답 실패 (Circuit open / 사용자 취소 등) */
    FAILED;

    fun isTerminal(): Boolean = this == APPROVED || this == FAILED
}
