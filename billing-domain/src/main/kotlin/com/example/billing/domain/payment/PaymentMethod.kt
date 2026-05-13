package com.example.billing.domain.payment

enum class PaymentMethod {
    CARD,
    BANK_TRANSFER,

    /** 사용자 지갑 잔액 차감 */
    WALLET,

    /** In-App Purchase (Google/Apple) */
    IAP,
}
