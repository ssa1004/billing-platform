package com.example.billing.domain.refund

enum class RefundStatus {
    REQUESTED,   // 환불 요청만 등록
    APPROVED,    // PG 환불 승인
    COMPLETED,   // 사용자 Wallet 환원 완료
    FAILED;

    fun isTerminal(): Boolean = this == COMPLETED || this == FAILED
}
