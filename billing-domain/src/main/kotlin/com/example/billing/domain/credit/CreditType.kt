package com.example.billing.domain.credit

/**
 * 크레딧 발급 사유.
 *
 * - [PROMO] — 마케팅/프로모션 (free trial, 신규 가입 등). 보통 짧은 만료.
 * - [PREPAID] — 고객이 선결제로 충전. 만료가 길거나 없음.
 * - [COMPENSATION] — 장애 / SLA 위반 보상. CS 발급.
 * - [REFUND_TO_CREDIT] — 환불을 현금 대신 크레딧으로 (재청구 회피).
 */
enum class CreditType {
    PROMO,
    PREPAID,
    COMPENSATION,
    REFUND_TO_CREDIT,
}
