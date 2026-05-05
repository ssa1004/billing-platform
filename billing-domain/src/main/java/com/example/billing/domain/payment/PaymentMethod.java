package com.example.billing.domain.payment;

public enum PaymentMethod {
    CARD,
    BANK_TRANSFER,
    WALLET,        // 사용자 지갑 잔액 차감
    IAP            // In-App Purchase (Google/Apple)
}
