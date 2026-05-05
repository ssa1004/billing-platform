package com.example.billing.adapter.out.pdf;

import com.example.billing.application.port.out.InvoicePdfRenderer;
import com.example.billing.domain.invoice.Invoice;
import com.example.billing.domain.invoice.InvoiceLine;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/**
 * 운영 PDF 라이브러리 (iText 등) 도입 전까지 사용하는 plain-text invoice 렌더러.
 *
 * <p>실제 PDF 가 아닌 text 를 byte[] 로 반환. controller 의 Content-Type 만 text/plain
 * 으로 바꾸면 동작. 운영용 구현은 같은 인터페이스로 교체.</p>
 */
@Component
@Profile("!prod")
public class MockInvoicePdfRenderer implements InvoicePdfRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    @Override
    public byte[] render(Invoice invoice) {
        StringBuilder sb = new StringBuilder();
        sb.append("==========================================\n");
        sb.append("            INVOICE\n");
        sb.append("==========================================\n");
        sb.append("Invoice ID:   ").append(invoice.id()).append('\n');
        sb.append("Customer:     ").append(invoice.customerId().value()).append('\n');
        sb.append("Period:       ").append(invoice.period().toKey()).append('\n');
        sb.append("Status:       ").append(invoice.status()).append('\n');
        sb.append("Issued at:    ")
                .append(invoice.issuedAt() != null
                        ? invoice.issuedAt().atZone(java.time.ZoneOffset.UTC).format(DATE)
                        : "-")
                .append('\n');
        sb.append("Due at:       ")
                .append(invoice.dueAt() != null
                        ? invoice.dueAt().atZone(java.time.ZoneOffset.UTC).format(DATE)
                        : "-")
                .append('\n');
        sb.append("\n");

        sb.append("Line Items:\n");
        sb.append("------------------------------------------\n");
        for (InvoiceLine line : invoice.lines()) {
            sb.append(String.format("  %-25s %12d %s%n",
                    line.resourceType() + " × " + line.quantity(),
                    line.lineTotal().amount().longValueExact(),
                    line.lineTotal().currency().getCurrencyCode()));
        }
        sb.append("------------------------------------------\n");
        sb.append(String.format("  %-25s %12d %s%n",
                "TOTAL",
                invoice.total().amount().longValueExact(),
                invoice.total().currency().getCurrencyCode()));
        sb.append("\n");

        sb.append("Pricing plan applied:\n");
        sb.append("  ").append(invoice.pricingSnapshot().planName())
                .append(" (snapshot at ")
                .append(invoice.pricingSnapshot().capturedAt()).append(")\n");
        sb.append("  Tiers: ").append(
                invoice.pricingSnapshot().tiers().stream()
                        .map(t -> t.resourceType()
                                + " up to " + (t.upTo() == null ? "∞" : t.upTo().toString())
                                + " @ " + t.unitPrice())
                        .collect(Collectors.joining("; "))
        ).append('\n');
        sb.append("==========================================\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
