package com.example.billing.adapter.web

import com.example.billing.adapter.web.dto.InvoiceResponse
import com.example.billing.application.port.out.InvoiceRepository
import com.example.billing.domain.shared.CustomerId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/invoices")
@Tag(name = "invoice", description = "청구서 조회")
class InvoiceController(
    private val invoiceRepository: InvoiceRepository,
) {

    @GetMapping("/{id}")
    @Operation(summary = "청구서 단건 조회")
    fun get(@PathVariable id: String): ResponseEntity<InvoiceResponse> {
        return invoiceRepository.findById(UUID.fromString(id))
            .map { ResponseEntity.ok(InvoiceResponse.from(it)) }
            .orElse(ResponseEntity.notFound().build())
    }

    @GetMapping
    @Operation(summary = "고객별 청구서 목록 (최근 N개)")
    fun listByCustomer(
        @RequestParam customerId: String,
        @RequestParam(defaultValue = "20") limit: Int,
    ): List<InvoiceResponse> {
        val invoices = invoiceRepository.findByCustomer(CustomerId.of(customerId), limit)
        return invoices.map(InvoiceResponse::from)
    }
}
