package com.example.billing.adapter.out.pdf

import com.example.billing.application.port.out.InvoicePdfRenderer
import com.example.billing.domain.invoice.Invoice
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 운영 PDF 라이브러리 (iText 등) 도입 전까지 사용하는 plain-text invoice 렌더러.
 *
 * 실제 PDF 가 아닌 text 를 byte[] 로 반환. controller 의 Content-Type 만 text/plain
 * 으로 바꾸면 동작. 운영용 구현은 같은 인터페이스로 교체.
 */
@Component
@Profile("!prod")
class MockInvoicePdfRenderer : InvoicePdfRenderer {

    override fun render(invoice: Invoice): ByteArray {
        val sb = StringBuilder()
        sb.append("==========================================\n")
        sb.append("            INVOICE\n")
        sb.append("==========================================\n")
        sb.append("Invoice ID:   ").append(invoice.id).append('\n')
        sb.append("Customer:     ").append(invoice.customerId.value).append('\n')
        sb.append("Period:       ").append(invoice.period.toKey()).append('\n')
        sb.append("Status:       ").append(invoice.status).append('\n')
        sb.append("Issued at:    ")
            .append(
                invoice.issuedAt?.atZone(ZoneOffset.UTC)?.format(DATE) ?: "-",
            )
            .append('\n')
        sb.append("Due at:       ")
            .append(
                invoice.dueAt?.atZone(ZoneOffset.UTC)?.format(DATE) ?: "-",
            )
            .append('\n')
        sb.append("\n")

        sb.append("Line Items:\n")
        sb.append("------------------------------------------\n")
        for (line in invoice.lines) {
            sb.append(
                String.format(
                    "  %-25s %12d %s%n",
                    "${line.resourceType} × ${line.quantity}",
                    line.lineTotal.amount.longValueExact(),
                    line.lineTotal.currency.currencyCode,
                ),
            )
        }
        sb.append("------------------------------------------\n")
        sb.append(
            String.format(
                "  %-25s %12d %s%n",
                "TOTAL",
                invoice.total.amount.longValueExact(),
                invoice.total.currency.currencyCode,
            ),
        )
        sb.append("\n")

        sb.append("Pricing plan applied:\n")
        sb.append("  ").append(invoice.pricingSnapshot.planName)
            .append(" (snapshot at ")
            .append(invoice.pricingSnapshot.capturedAt).append(")\n")
        sb.append("  Tiers: ").append(
            invoice.pricingSnapshot.tiers.joinToString("; ") { t ->
                "${t.resourceType} up to ${t.upTo?.toString() ?: "∞"} @ ${t.unitPrice}"
            },
        ).append('\n')
        sb.append("==========================================\n")
        return sb.toString().toByteArray(StandardCharsets.UTF_8)
    }

    companion object {
        private val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
