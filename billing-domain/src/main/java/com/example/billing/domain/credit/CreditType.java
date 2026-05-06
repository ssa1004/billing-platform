package com.example.billing.domain.credit;

/**
 * 크레딧 발급 사유.
 *
 * <ul>
 *   <li>{@link #PROMO} — 마케팅/프로모션 (free trial, 신규 가입 등). 보통 짧은 만료.</li>
 *   <li>{@link #PREPAID} — 고객이 선결제로 충전. 만료가 길거나 없음.</li>
 *   <li>{@link #COMPENSATION} — 장애 / SLA 위반 보상. CS 발급.</li>
 *   <li>{@link #REFUND_TO_CREDIT} — 환불을 현금 대신 크레딧으로 (재청구 회피).</li>
 * </ul>
 */
public enum CreditType {
    PROMO,
    PREPAID,
    COMPENSATION,
    REFUND_TO_CREDIT
}
