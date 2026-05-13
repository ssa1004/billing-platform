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
)

fun Invoice.toResponse(): InvoiceResponse = InvoiceResponse(
    id = id().toString(),
    customerId = customerId().value,
    period = period().toKey(),
    status = status(),
    total = total().amount,
    currency = total().currency.currencyCode,
    lines = lines().map {
        InvoiceLineResponse(
            it.resourceType().name, it.quantity(), it.lineTotal().amount,
            it.lineTotal().currency.currencyCode, it.unitPriceDescription(),
        )
    },
    issuedAt = issuedAt(),
    dueAt = dueAt(),
    paidAt = paidAt(),
)
