package com.example.billing.adapter.web

import com.example.billing.adapter.web.dto.ApplyCreditRequest
import com.example.billing.adapter.web.dto.ApplyCreditResponse
import com.example.billing.adapter.web.dto.GrantCreditRequest
import com.example.billing.adapter.web.dto.GrantCreditResponse
import com.example.billing.application.command.ApplyCreditCommand
import com.example.billing.application.command.GrantCreditCommand
import com.example.billing.application.port.`in`.ApplyCreditUseCase
import com.example.billing.application.port.`in`.GrantCreditUseCase
import com.example.billing.domain.credit.CreditType
import com.example.billing.domain.shared.Money
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.Currency
import java.util.UUID

/**
 * 크레딧 운영 API. 운영자/CS/마케팅 시스템이 호출.
 *
 * <p>발급은 단순 잔액 +X 가 아니라 사유와 유효기간을 함께 기록 — 회계/만료/회수에 영향.
 * 자세한 설계 의도는 ADR-0018.</p>
 */
@RestController
@RequestMapping("/api/v1/credits")
@Tag(name = "credit", description = "크레딧 발급 / 적용")
class CreditController(
    private val grantCredit: GrantCreditUseCase,
    private val applyCredit: ApplyCreditUseCase,
) {

    @PostMapping
    @Operation(summary = "크레딧 발급 (PROMO / PREPAID / COMPENSATION / REFUND_TO_CREDIT)")
    fun grant(@Valid @RequestBody req: GrantCreditRequest): ResponseEntity<GrantCreditResponse> {
        val currency = Currency.getInstance(req.currency)
        val cmd = GrantCreditCommand(
            req.customerId,
            CreditType.valueOf(req.type),
            Money.of(req.amount, currency),
            Instant.parse(req.validFrom),
            req.validUntil?.let(Instant::parse),
            req.reason,
        )
        val credit = grantCredit.grant(cmd)
        return ResponseEntity.ok(
            GrantCreditResponse(
                creditId = credit.id().toString(),
                customerId = credit.customerId().value(),
                type = credit.type().name,
                grantedAmount = credit.grantedAmount().amount(),
                currency = credit.currency().currencyCode,
                validFrom = credit.validFrom().toString(),
                validUntil = credit.validUntil()?.toString(),
                status = credit.status().name,
            )
        )
    }

    @PostMapping("/apply")
    @Operation(summary = "사용 가능한 크레딧을 invoice 에 적용 (만료 임박 우선)")
    fun apply(@Valid @RequestBody req: ApplyCreditRequest): ResponseEntity<ApplyCreditResponse> {
        val currency = Currency.getInstance(req.currency)
        val cmd = ApplyCreditCommand(
            req.customerId,
            UUID.fromString(req.invoiceId),
            Money.of(req.applyAtMost, currency),
        )
        val applied = applyCredit.apply(cmd)
        return ResponseEntity.ok(
            ApplyCreditResponse(
                invoiceId = req.invoiceId,
                customerId = req.customerId,
                appliedAmount = applied.amount(),
                currency = applied.currency().currencyCode,
            )
        )
    }
}
