package com.example.billing.adapter.web.v2

import com.example.billing.adapter.web.dto.v2.InvoiceV2Response
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

/**
 * 청구서 조회 v2 (ADR-0031).
 *
 * v1 ([com.example.billing.adapter.web.InvoiceController]) 와 *별도 controller* — v1 은
 * unchanged 로 유지. 같은 도메인 객체를 v2 DTO 로 매핑해 반환.
 *
 * v2 추가 항목:
 *  - 응답에 `appliedCredit`, `amountDue` 노출 (v1 은 total 만)
 *  - line 의 화폐를 `MoneyV2 { amount, currency }` 객체로 표준화 (v1 은 두 필드 분리)
 *  - currencyFilter query 파라미터 (옵션) — 특정 통화 invoice 만 조회
 *
 * v1 → v2 마이그레이션 grace 6개월. 실제 v1 → v2 cutover 는 *운영 metric* 으로 v1 사용량이
 * 충분히 떨어진 시점에 결정 (ADR-0031 의 "ApiVersionMetricsFilter" 참조).
 */
@RestController
@RequestMapping("/api/v2/invoices")
@Tag(name = "invoice-v2", description = "청구서 조회 v2 — appliedCredit / amountDue 포함")
class InvoiceV2Controller(
    private val invoiceRepository: InvoiceRepository,
) {

    @GetMapping("/{id}")
    @Operation(summary = "청구서 단건 조회 v2")
    fun get(@PathVariable id: String): ResponseEntity<InvoiceV2Response> {
        return invoiceRepository.findById(UUID.fromString(id))
            .map { ResponseEntity.ok(InvoiceV2Response.from(it)) }
            .orElse(ResponseEntity.notFound().build())
    }

    @GetMapping
    @Operation(summary = "고객별 청구서 목록 v2 (최근 N개)")
    fun listByCustomer(
        @RequestParam customerId: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) currency: String?,
    ): List<InvoiceV2Response> {
        val invoices = invoiceRepository.findByCustomer(CustomerId.of(customerId), limit)
        val filtered = if (currency.isNullOrBlank()) {
            invoices
        } else {
            invoices.filter { it.total().currency().currencyCode == currency }
        }
        return filtered.map(InvoiceV2Response::from)
    }
}
