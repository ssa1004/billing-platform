package com.example.billing.domain.payment;

public enum PaymentStatus {
    PENDING,    // PG 호출 전
    APPROVED,   // PG 응답 OK
    FAILED;     // PG 응답 실패 (Circuit open / 사용자 취소 등)

    public boolean isTerminal() { return this == APPROVED || this == FAILED; }
}
