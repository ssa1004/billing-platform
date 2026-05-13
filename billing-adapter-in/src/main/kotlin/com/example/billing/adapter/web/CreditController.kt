package com.example.billing.adapter.web

import com.example.billing.adapter.web.auth.Caller
import com.example.billing.adapter.web.dto.ApplyCreditRequest
import com.example.billing.adapter.web.dto.ApplyCreditResponse
import com.example.billing.adapter.web.dto.CreditBalanceResponse
import com.example.billing.adapter.web.dto.CreditListResponse
import com.example.billing.adapter.web.dto.CreditView
import com.example.billing.adapter.web.dto.GrantCreditRequest
import com.example.billing.adapter.web.dto.GrantCreditResponse
import com.example.billing.application.command.ApplyCreditCommand
import com.example.billing.application.command.GrantCreditCommand
import com.example.billing.application.port.`in`.ApplyCreditUseCase
import com.example.billing.application.port.`in`.CustomerCreditQueryUseCase
import com.example.billing.application.port.`in`.GrantCreditUseCase
import com.example.billing.domain.credit.Credit
import com.example.billing.domain.credit.CreditType
import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.shared.Money
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.util.Currency
import java.util.UUID

/**
 * 크레딧 운영 API. 운영자/CS/마케팅 시스템이 호출.
 *
 * <p>발급은 단순 잔액 +X 가 아니라 사유와 유효기간을 함께 기록 — 회계/만료/회수에 영향.
 * 자세한 설계 의도는 ADR-0018.</p>
 *
 * <p><b>접근 제어 (OWASP API1 BOLA + API5)</b>:
 * <ul>
 *   <li>{@code POST /credits} (grant) — 운영자/마케팅이 customer 한테 발급. ADMIN 전용.</li>
 *   <li>{@code POST /credits/apply} — 본인 invoice 거나 admin. customer 자신은 자기 invoice 에만 적용.</li>
 *   <li>{@code GET /credits*} — 본인 자원 조회거나 admin.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/credits")
@Tag(name = "credit", description = "크레딧 발급 / 적용 / 조회")
class CreditController(
    private val grantCredit: GrantCreditUseCase,
    private val applyCredit: ApplyCreditUseCase,
    private val query: CustomerCreditQueryUseCase,
) {

    @PostMapping
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "크레딧 발급 (PROMO / PREPAID / COMPENSATION / REFUND_TO_CREDIT)")
    fun grant(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody req: GrantCreditRequest,
    ): ResponseEntity<GrantCreditResponse> {
        val currency = Currency.getInstance(req.currency)
        val cmd = GrantCreditCommand(
            idempotencyKey,
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
                creditId = credit.id.toString(),
                customerId = credit.customerId.value,
                type = credit.type.name,
                grantedAmount = credit.grantedAmount.amount,
                currency = credit.currency.currencyCode,
                validFrom = credit.validFrom.toString(),
                validUntil = credit.validUntil?.toString(),
                status = credit.status.name,
            )
        )
    }

    @PostMapping("/apply")
    @Operation(summary = "사용 가능한 크레딧을 invoice 에 적용 (만료 임박 우선)")
    fun apply(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody req: ApplyCreditRequest,
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<ApplyCreditResponse> {
        Caller.from(jwt).requireOwnerOrAdmin(req.customerId)
        val currency = Currency.getInstance(req.currency)
        val cmd = ApplyCreditCommand(
            idempotencyKey,
            req.customerId,
            UUID.fromString(req.invoiceId),
            Money.of(req.applyAtMost, currency),
        )
        val applied = applyCredit.apply(cmd)
        return ResponseEntity.ok(
            ApplyCreditResponse(
                invoiceId = req.invoiceId,
                customerId = req.customerId,
                appliedAmount = applied.amount,
                currency = applied.currency.currencyCode,
            )
        )
    }

    @GetMapping("/balance")
    @Operation(summary = "고객 단위 사용 가능 크레딧 잔액 (통화별)")
    fun balance(
        @RequestParam customerId: String,
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<CreditBalanceResponse> {
        Caller.from(jwt).requireOwnerOrAdmin(customerId)
        val sums = query.usableBalances(CustomerId.of(customerId))
        return ResponseEntity.ok(
            CreditBalanceResponse(
                customerId = customerId,
                balances = sums.entries.map {
                    CreditBalanceResponse.CurrencyBalance(it.key.currencyCode, it.value.amount)
                },
            )
        )
    }

    @GetMapping
    @Operation(summary = "고객의 모든 Credit (status 무관, 최근 발급 우선)")
    fun list(
        @RequestParam customerId: String,
        @RequestParam(defaultValue = "100") limit: Int,
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<CreditListResponse> {
        Caller.from(jwt).requireOwnerOrAdmin(customerId)
        val items = query.findAll(CustomerId.of(customerId), limit.coerceIn(1, MAX_LIMIT)).map(::toView)
        return ResponseEntity.ok(CreditListResponse(items = items))
    }

    @GetMapping("/expiring")
    @Operation(summary = "만료 임박 크레딧 — 알림 / 사용자 대시보드용")
    fun expiring(
        @RequestParam customerId: String,
        @RequestParam(defaultValue = "7") withinDays: Long,
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<CreditListResponse> {
        Caller.from(jwt).requireOwnerOrAdmin(customerId)
        val items = query.findExpiringSoon(CustomerId.of(customerId), Duration.ofDays(withinDays.coerceIn(1, MAX_WITHIN_DAYS)))
            .map(::toView)
        return ResponseEntity.ok(CreditListResponse(items = items))
    }

    private fun toView(c: Credit): CreditView = CreditView(
        id = c.id.toString(),
        customerId = c.customerId.value,
        type = c.type.name,
        currency = c.currency.currencyCode,
        grantedAmount = c.grantedAmount.amount,
        balance = c.balance.amount,
        validFrom = c.validFrom.toString(),
        validUntil = c.validUntil?.toString(),
        status = c.status.name,
        reason = c.reason,
    )

    companion object {
        /** OWASP API4 — Unrestricted Resource Consumption cap. */
        private const val MAX_LIMIT = 200
        private const val MAX_WITHIN_DAYS = 365L
    }
}
