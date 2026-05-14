package com.example.billing.domain.invoice

import com.example.billing.domain.metering.ResourceType
import com.example.billing.domain.shared.Money

/**
 * 청구서 line item — 한 resourceType 의 사용량과 청구 금액.
 *
 * [unitPriceDescription] 은 사람이 읽는 가격 설명 (예: "처음 1만 호출 무료, 이후 호출당 1원").
 * UI 표시 + 영수증 발행에 사용.
 *
 * `@JvmRecord data class` — Java 호출자 (`JpaInvoiceRepositoryAdapter` 의 `new InvoiceLine(...)`
 * 직접 생성자 + `line.resourceType()` / `line.lineTotal()` 등 record-style accessor,
 * `MockInvoicePdfRenderer` 의 `for (InvoiceLine line : invoice.lines())`) 무변경.
 * compact constructor 검증은 `init` 블록으로 보존.
 */
@JvmRecord
data class InvoiceLine(
    val resourceType: ResourceType,
    val quantity: Long,
    val lineTotal: Money,
    val unitPriceDescription: String,
) {

    init {
        require(quantity >= 0) { "quantity must be non-negative" }
    }
}
