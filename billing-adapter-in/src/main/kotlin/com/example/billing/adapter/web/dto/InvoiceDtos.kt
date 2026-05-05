package com.example.billing.adapter.web.dto

import com.example.billing.domain.invoice.Invoice
import com.example.billing.domain.invoice.InvoiceStatus
import java.math.BigDecimal
import java.time.Instant

data class InvoiceLineResponse(
    val resourceType: String,
    val quantity: Long,
    val amount: BigDecimal,
    val currency: String,
    val description: String,
)

data class InvoiceResponse(
    val id: String,
    val customerId: String,
    val period: String,
    val status: InvoiceStatus,
    val total: BigDecimal,
    val currency: String,
    val lines: List<InvoiceLineResponse>,
    val issuedAt: Instant?,
    val dueAt: Instant?,
    val paidAt: Instant?,
) {
    companion object {
        fun from(i: Invoice) = InvoiceResponse(
            id = i.id().toString(),
            customerId = i.customerId().value(),
            period = i.period().toKey(),
            status = i.status(),
            total = i.total().amount(),
            currency = i.total().currency().currencyCode,
            lines = i.lines().map {
                InvoiceLineResponse(
                    it.resourceType().name, it.quantity(), it.lineTotal().amount(),
                    it.lineTotal().currency().currencyCode, it.unitPriceDescription(),
                )
            },
            issuedAt = i.issuedAt(),
            dueAt = i.dueAt(),
            paidAt = i.paidAt(),
        )
    }
}
