package com.example.billing.adapter.web.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal

/** 크레딧 발급 요청. */
data class GrantCreditRequest(
    @field:NotBlank @field:Size(max = 64) val customerId: String,
    @field:NotBlank @field:Size(max = 32) val type: String,    // PROMO / PREPAID / COMPENSATION / REFUND_TO_CREDIT
    @field:Positive val amount: BigDecimal,
    @field:NotBlank @field:Size(min = 3, max = 3) val currency: String,   // ISO 4217
    @field:NotBlank val validFrom: String,                                // ISO-8601
    val validUntil: String? = null,                                       // ISO-8601, null = 만료 없음
    @field:Size(max = 256) val reason: String? = null,
)

data class GrantCreditResponse(
    val creditId: String,
    val customerId: String,
    val type: String,
    val grantedAmount: BigDecimal,
    val currency: String,
    val validFrom: String,
    val validUntil: String?,
    val status: String,
)

/** 청구서에 크레딧 적용 요청. */
data class ApplyCreditRequest(
    @field:NotBlank @field:Size(max = 64) val customerId: String,
    @field:NotBlank @field:Size(max = 64) val invoiceId: String,
    @field:Positive val applyAtMost: BigDecimal,
    @field:NotBlank @field:Size(min = 3, max = 3) val currency: String,
)

data class ApplyCreditResponse(
    val invoiceId: String,
    val customerId: String,
    val appliedAmount: BigDecimal,
    val currency: String,
)

/** 통화별 사용 가능 잔액. */
data class CreditBalanceResponse(
    val customerId: String,
    val balances: List<CurrencyBalance>,
) {
    data class CurrencyBalance(val currency: String, val amount: BigDecimal)
}

/** Credit 단건 view (응답용 — 도메인 노출 회피). */
data class CreditView(
    val id: String,
    val customerId: String,
    val type: String,
    val currency: String,
    val grantedAmount: BigDecimal,
    val balance: BigDecimal,
    val validFrom: String,
    val validUntil: String?,
    val status: String,
    val reason: String?,
)

data class CreditListResponse(val items: List<CreditView>)

