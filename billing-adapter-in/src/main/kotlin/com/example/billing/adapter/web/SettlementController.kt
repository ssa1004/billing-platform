package com.example.billing.adapter.web

import com.example.billing.application.command.RunSettlementCommand
import com.example.billing.application.command.SettlementResult
import com.example.billing.application.port.`in`.RunSettlementUseCase
import com.example.billing.domain.settlement.BillingPeriod
import com.example.billing.domain.shared.CustomerId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.YearMonth

/**
 * 운영자 수동 정산 트리거. 평상시는 MonthlySettlementJob (Spring Batch) 가 처리.
 *
 * <p><b>OWASP API5 Broken Function Level Auth</b>: 정산 실행은 회계 / 운영자 권한.
 * 일반 사용자가 임의 customer × period 정산을 trigger 하면 invoice / outbox 가 잘못 생성되어
 * 도메인 invariant 가 흔들림.</p>
 */
@RestController
@RequestMapping("/api/v1/settlement")
@Tag(name = "settlement", description = "정산 운영 (수동 트리거)")
@PreAuthorize("hasRole('admin')")
class SettlementController(
    private val runSettlement: RunSettlementUseCase,
) {

    @PostMapping("/run")
    @Operation(summary = "특정 customer × period 정산 실행 (운영자 전용)")
    fun run(
        @RequestParam customerId: String,
        @RequestParam period: String,  // "2026-05"
    ): SettlementResult {
        val cmd = RunSettlementCommand(
            CustomerId.of(customerId),
            BillingPeriod.of(YearMonth.parse(period)),
        )
        return runSettlement.run(cmd)
    }
}
