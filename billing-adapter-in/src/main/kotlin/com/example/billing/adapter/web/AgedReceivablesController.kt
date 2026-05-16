package com.example.billing.adapter.web

import com.example.billing.application.service.AgedReceivablesService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant

/**
 * 운영 / 회계 화면용 미수금 조회 endpoint.
 *
 * <p><b>OWASP API5 Broken Function Level Auth</b>: 전체 customer 의 미수금 분포는 회계 / 운영
 * 데이터 — 일반 사용자에게 노출 금지.</p>
 */
@RestController
@RequestMapping("/api/v1/aged-receivables")
@Tag(name = "aged-receivables", description = "미수금 분석 (운영자 전용)")
@PreAuthorize("hasRole('admin')")
class AgedReceivablesController(
    private val agedReceivables: AgedReceivablesService,
) {

    @GetMapping
    @Operation(summary = "(고객 × 통화) 별 aging bucket (0-30 / 31-60 / 61-90 / 90+ 일) 미수 금액")
    fun report(): AgedReportDto {
        val report = agedReceivables.report()
        val rows = report.byCustomerCurrency.map { (key, buckets) ->
            CustomerAgingDto(
                customerId = key.customerId.value,
                currency = key.currency.currencyCode,
                current = buckets.current.amount,
                over30 = buckets.over30.amount,
                over60 = buckets.over60.amount,
                over90 = buckets.over90.amount,
                total = buckets.total.amount,
            )
        }
        return AgedReportDto(report.asOf, rows)
    }
}

data class AgedReportDto(val asOf: Instant, val rows: List<CustomerAgingDto>)

data class CustomerAgingDto(
    val customerId: String,
    val currency: String,
    val current: BigDecimal,
    val over30: BigDecimal,
    val over60: BigDecimal,
    val over90: BigDecimal,
    val total: BigDecimal,
)
