package com.example.billing.domain.credit;

/**
 * Credit 라이프사이클 상태.
 *
 * <pre>
 *   ACTIVE ──consume(전액)──▶ EXHAUSTED   (잔액 0)
 *      │
 *      ├──validUntil 도달──▶ EXPIRED      (스케줄러가 처리)
 *      │
 *      └──운영자 강제 회수──▶ REVOKED      (사기/오류 정정 등)
 * </pre>
 *
 * <p>한 번 종착 상태에 들어가면 재활성 불가.</p>
 */
public enum CreditStatus {
    ACTIVE,
    EXHAUSTED,
    EXPIRED,
    REVOKED
}
