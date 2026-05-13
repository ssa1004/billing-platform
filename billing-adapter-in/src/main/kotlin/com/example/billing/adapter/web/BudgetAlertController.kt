package com.example.billing.adapter.web

import com.example.billing.adapter.web.auth.Caller
import com.example.billing.adapter.web.dto.BudgetAlertHistoryResponse
import com.example.billing.adapter.web.dto.BudgetAlertHistoryView
import com.example.billing.adapter.web.dto.BudgetAlertRuleListResponse
import com.example.billing.adapter.web.dto.BudgetAlertRuleView
import com.example.billing.adapter.web.dto.CreateBudgetAlertRuleRequest
import com.example.billing.application.command.CreateBudgetAlertRuleCommand
import com.example.billing.application.exception.BudgetAlertRuleNotFoundException
import com.example.billing.application.port.`in`.BudgetAlertHistoryQueryUseCase
import com.example.billing.application.port.`in`.BudgetAlertRuleLifecycleUseCase
import com.example.billing.application.port.`in`.CreateBudgetAlertRuleUseCase
import com.example.billing.application.port.out.BudgetAlertRuleRepository
import com.example.billing.domain.budget.BudgetAlertHistoryEntry
import com.example.billing.domain.budget.BudgetAlertRule
import com.example.billing.domain.budget.BudgetAlertRuleId
import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.shared.Money
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.Currency
import kotlin.jvm.optionals.getOrNull

/**
 * Budget alert rule 운영 API.
 *
 * <p>알림 발송은 별도 batch (매시간) 가 처리. 본 controller 는 rule CRUD 만.</p>
 *
 * <p><b>OWASP API1 BOLA</b>: customer 는 자기 자원만, admin 은 모든 자원에 접근.</p>
 */
@RestController
@RequestMapping("/api/v1/budget-alerts")
@Tag(name = "budget-alert", description = "예산 임계 알림 규칙")
class BudgetAlertController(
    private val createRule: CreateBudgetAlertRuleUseCase,
    private val lifecycle: BudgetAlertRuleLifecycleUseCase,
    private val historyQuery: BudgetAlertHistoryQueryUseCase,
    private val rules: BudgetAlertRuleRepository,
) {

    @PostMapping
    @Operation(summary = "Budget alert rule 생성")
    fun create(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody req: CreateBudgetAlertRuleRequest,
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<BudgetAlertRuleView> {
        Caller.from(jwt).requireOwnerOrAdmin(req.customerId)
        val currency = Currency.getInstance(req.currency)
        val cmd = CreateBudgetAlertRuleCommand(
            idempotencyKey,
            req.customerId,
            Money.of(req.threshold, currency),
            req.cooldown?.let(Duration::parse),
        )
        val rule = createRule.create(cmd)
        return ResponseEntity.ok(toView(rule))
    }

    @GetMapping
    @Operation(summary = "고객의 모든 budget alert rule 조회")
    fun list(
        @RequestParam customerId: String,
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<BudgetAlertRuleListResponse> {
        Caller.from(jwt).requireOwnerOrAdmin(customerId)
        val items = rules.findByCustomer(CustomerId.of(customerId)).map(::toView)
        return ResponseEntity.ok(BudgetAlertRuleListResponse(items = items))
    }

    @PostMapping("/{ruleId}/pause")
    @Operation(summary = "Rule 일시 정지 (ACTIVE → PAUSED)")
    fun pause(
        @PathVariable ruleId: String,
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<Void> {
        authorizeRule(ruleId, jwt)
        lifecycle.pause(BudgetAlertRuleId.of(ruleId))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{ruleId}/resume")
    @Operation(summary = "Rule 재개 (PAUSED → ACTIVE)")
    fun resume(
        @PathVariable ruleId: String,
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<Void> {
        authorizeRule(ruleId, jwt)
        lifecycle.resume(BudgetAlertRuleId.of(ruleId))
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{ruleId}/history")
    @Operation(summary = "특정 rule 의 트리거 이력 (최근 → 과거)")
    fun historyByRule(
        @PathVariable ruleId: String,
        @RequestParam(defaultValue = "100") limit: Int,
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<BudgetAlertHistoryResponse> {
        authorizeRule(ruleId, jwt)
        val items = historyQuery.findByRule(BudgetAlertRuleId.of(ruleId), limit.coerceIn(1, MAX_LIMIT))
            .map(::toHistoryView)
        return ResponseEntity.ok(BudgetAlertHistoryResponse(items = items))
    }

    @GetMapping("/history")
    @Operation(summary = "Customer 의 모든 rule 통합 트리거 이력")
    fun historyByCustomer(
        @RequestParam customerId: String,
        @RequestParam(defaultValue = "100") limit: Int,
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<BudgetAlertHistoryResponse> {
        Caller.from(jwt).requireOwnerOrAdmin(customerId)
        val items = historyQuery.findByCustomer(CustomerId.of(customerId), limit.coerceIn(1, MAX_LIMIT))
            .map(::toHistoryView)
        return ResponseEntity.ok(BudgetAlertHistoryResponse(items = items))
    }

    /**
     * rule path-variable 만 노출된 endpoint 의 ownership 검사 — rule 을 한 번 로드해 customer
     * 를 추출한 뒤 caller 와 매칭. admin 은 통과. rule 자체가 없으면 도메인 표준대로
     * [BudgetAlertRuleNotFoundException].
     */
    private fun authorizeRule(ruleId: String, jwt: Jwt?) {
        val rule = rules.findById(BudgetAlertRuleId.of(ruleId)).getOrNull()
            ?: throw BudgetAlertRuleNotFoundException(BudgetAlertRuleId.of(ruleId))
        Caller.from(jwt).requireOwnerOrAdmin(rule.customerId().value())
    }

    private fun toHistoryView(h: BudgetAlertHistoryEntry): BudgetAlertHistoryView =
        BudgetAlertHistoryView(
            id = h.id().toString(),
            ruleId = h.ruleId().toString(),
            customerId = h.customerId().value(),
            thresholdAtTrigger = h.thresholdAtTrigger().amount(),
            projectedCostAtTrigger = h.projectedCostAtTrigger().amount(),
            currency = h.thresholdAtTrigger().currency().currencyCode,
            overshootRatio = h.overshootRatio(),
            period = h.period().toKey(),
            periodProgressRatioAtTrigger = h.periodProgressRatioAtTrigger(),
            occurredAt = h.occurredAt().toString(),
        )

    private fun toView(r: BudgetAlertRule): BudgetAlertRuleView = BudgetAlertRuleView(
        id = r.id().toString(),
        customerId = r.customerId().value(),
        threshold = r.threshold().amount(),
        currency = r.threshold().currency().currencyCode,
        cooldownSeconds = r.cooldown().seconds,
        status = r.status().name,
        lastEvaluatedAt = r.lastEvaluatedAt()?.toString(),
        lastTriggeredAt = r.lastTriggeredAt()?.toString(),
        createdAt = r.createdAt().toString(),
    )

    companion object {
        /** OWASP API4 — Unrestricted Resource Consumption cap. */
        private const val MAX_LIMIT = 200
    }
}
