package com.example.billing.domain.invoice;

/**
 * 청구서 라이프사이클.
 *
 * <pre>
 *  DRAFT (생성 직후)
 *    ↓ issue
 *  ISSUED (발행, 결제 대기)
 *    ↓ markPaid              ↓ overdue (due date 경과)
 *  PAID                      OVERDUE
 *                              ↓ markPaid (지연 결제)
 *                            PAID
 * </pre>
 *
 * CANCELLED 는 어느 상태에서도 도달 가능 (관리자 강제 취소).
 */
public enum InvoiceStatus {
    DRAFT, ISSUED, PAID, OVERDUE, CANCELLED;

    public boolean isFinal() {
        return this == PAID || this == CANCELLED;
    }
}
