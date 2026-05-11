package com.example.billing.adapter.web.dto.v2

import com.example.billing.domain.invoice.Invoice
import com.example.billing.domain.invoice.InvoiceStatus
import java.math.BigDecimal
import java.time.Instant

/**
 * v2 응답 — v1 의 InvoiceResponse 와 별도 클래스 (ADR-0031).
 *
 * v1 대비 추가:
 *  - `appliedCredit`  — 이 invoice 에 적용된 credit 누적
 *  - `amountDue`      — total - appliedCredit (실제 결제 대상)
 *  - `currencyCode`   — ISO-4217 코드 (v1 은 line 안에만 노출됨)
 *
 * line 안의 화폐 정보도 변경 — v1 의 `currency` 필드는 v2 에서 line 의 amount 가 객체
 * (`amount` + `currency`) 로 풀어 표현되도록 통일. 읽는 쪽 변환 코드 줄임.
 *
 * v1 → v2 마이그레이션은 6개월 grace — v1 controller 는 unchanged 그대로 유지.
 */
data class MoneyV2(
    val amount: BigDecimal,
    val currency: String,
)

data class InvoiceLineV2Response(
    val resourceType: String,
    val quantity: Long,
    val lineTotal: MoneyV2,
    val description: String,
)

data class InvoiceV2Response(
    val id: String,
    val customerId: String,
    val period: String,
    val status: InvoiceStatus,
    val total: MoneyV2,
    val appliedCredit: MoneyV2,    // v2 추가
    val amountDue: MoneyV2,        // v2 추가
    val lines: List<InvoiceLineV2Response>,
    val issuedAt: Instant?,
    val dueAt: Instant?,
    val paidAt: Instant?,
)

private fun com.example.billing.domain.shared.Money.toV2(): MoneyV2 =
    MoneyV2(amount(), currency().currencyCode)

fun Invoice.toV2Response(): InvoiceV2Response = InvoiceV2Response(
    id = id().toString(),
    customerId = customerId().value(),
    period = period().toKey(),
    status = status(),
    total = total().toV2(),
    appliedCredit = appliedCredit().toV2(),
    amountDue = amountDue().toV2(),
    lines = lines().map {
        InvoiceLineV2Response(
            resourceType = it.resourceType().name,
            quantity = it.quantity(),
            lineTotal = it.lineTotal().toV2(),
            description = it.unitPriceDescription(),
        )
    },
    issuedAt = issuedAt(),
    dueAt = dueAt(),
    paidAt = paidAt(),
)
