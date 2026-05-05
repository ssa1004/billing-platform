package com.example.wallet.domain.refund;

public enum RefundStatus {
    REQUESTED,   // 환불 요청만 등록
    APPROVED,    // PG 환불 승인
    COMPLETED,   // 사용자 Wallet 환원 완료
    FAILED;

    public boolean isTerminal() { return this == COMPLETED || this == FAILED; }
}
