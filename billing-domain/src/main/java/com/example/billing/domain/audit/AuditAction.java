package com.example.billing.domain.audit;

/**
 * Audit log 에 기록되는 행위 분류.
 *
 * <p>각 값은 *과거 시제 영문* — "이미 일어난 사실" 을 기록하는 audit log 의 본질.
 * (예: {@link #INVOICE_ISSUED} ✓ vs {@link #INVOICE_ISSUE} ✗)</p>
 *
 * <p><b>새 행위 추가는 신중히</b>: 한번 enum 에 들어간 값은 *과거 row 의 의미가 박혀* 있어
 * 쉽게 못 뺀다. rename / 삭제는 마이그레이션 + 다운스트림 컨슈머 (분석 / BI) 동의 필요.</p>
 */
public enum AuditAction {

    // ── invoice ──
    INVOICE_ISSUED,
    INVOICE_PAID,
    INVOICE_OVERDUE,
    INVOICE_CANCELLED,
    INVOICE_CREDIT_APPLIED,

    // ── payment ──
    PAYMENT_AUTHORIZED,
    PAYMENT_REJECTED,
    PAYMENT_VOIDED,

    // ── refund ──
    REFUND_REQUESTED,
    REFUND_APPROVED,
    REFUND_FAILED,

    // ── credit ──
    CREDIT_GRANTED,
    CREDIT_REVOKED,
    CREDIT_EXPIRED,

    // ── wallet ──
    WALLET_DEPOSIT,
    WALLET_WITHDRAWAL,
    WALLET_BLOCKED,

    // ── webhook 운영 ──
    WEBHOOK_ENDPOINT_REGISTERED,
    WEBHOOK_ENDPOINT_PAUSED,
    WEBHOOK_ENDPOINT_RESUMED,
    WEBHOOK_SECRET_ROTATED,

    // ── budget alert 운영 ──
    BUDGET_RULE_CREATED,
    BUDGET_RULE_PAUSED,
    BUDGET_RULE_RESUMED,

    // ── 보안성 운영자 작업 ──
    OPERATOR_LOGIN,
    OPERATOR_DATA_EXPORT,
    OPERATOR_PERMISSION_CHANGED,
}
