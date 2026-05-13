package com.example.billing.adapter.web

import com.example.billing.adapter.web.auth.Caller
import com.example.billing.adapter.web.dto.InvoiceResponse
import com.example.billing.adapter.web.dto.toResponse
import com.example.billing.application.port.out.InvoicePdfRenderer
import com.example.billing.application.port.out.InvoiceRepository
import com.example.billing.domain.invoice.Invoice
import com.example.billing.domain.shared.CustomerId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@RestController
@RequestMapping("/api/v1/invoices")
@Tag(name = "invoice", description = "청구서 조회 및 PDF 다운로드")
class InvoiceController(
    private val invoiceRepository: InvoiceRepository,
    private val pdfRenderer: InvoicePdfRenderer,
) {

    @GetMapping("/{id}")
    @Operation(summary = "청구서 단건 조회")
    fun get(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable id: String,
    ): ResponseEntity<InvoiceResponse> {
        val invoice = invoiceRepository.findById(UUID.fromString(id)).getOrNull()
            ?: return ResponseEntity.notFound().build()
        // BOLA (OWASP API1) — invoice 의 customer 가 caller 자신이거나 admin 일 때만 응답.
        Caller.from(jwt).requireOwnerOrAdmin(invoice.customerId().value)
        return ResponseEntity.ok(invoice.toResponse())
    }

    @GetMapping
    @Operation(summary = "고객별 청구서 목록 (최근 N개)")
    fun listByCustomer(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestParam customerId: String,
        @RequestParam(defaultValue = "20") limit: Int,
    ): List<InvoiceResponse> {
        Caller.from(jwt).requireOwnerOrAdmin(customerId)
        return invoiceRepository.findByCustomer(CustomerId.of(customerId), boundLimit(limit))
            .map(Invoice::toResponse)
    }

    @GetMapping("/{id}/pdf")
    @Operation(summary = "청구서 PDF 다운로드")
    fun downloadPdf(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable id: String,
    ): ResponseEntity<ByteArray> {
        val invoice = invoiceRepository.findById(UUID.fromString(id)).getOrNull()
            ?: return ResponseEntity.notFound().build()
        Caller.from(jwt).requireOwnerOrAdmin(invoice.customerId().value)

        val bytes = pdfRenderer.render(invoice)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"invoice-${invoice.period().toKey()}-${invoice.id()}.pdf\""
            )
            .body(bytes)
    }

    /**
     * 클라이언트가 무한대 limit 으로 대량 스캔하는 것을 막음 (OWASP API4 — Unrestricted
     * Resource Consumption). 운영 cap 은 200 — UI 페이지 사이즈 100 의 2배 여유.
     */
    private fun boundLimit(limit: Int): Int = limit.coerceIn(1, MAX_LIMIT)

    companion object {
        private const val MAX_LIMIT = 200
    }
}
