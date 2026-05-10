package com.example.billing.adapter.web

import com.example.billing.adapter.web.dto.RefundRequest
import com.example.billing.adapter.web.dto.RefundResponse
import com.example.billing.adapter.web.dto.toResponse
import com.example.billing.application.port.`in`.RefundUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/refunds")
@Tag(name = "refunds", description = "환불 처리")
class RefundController(
    private val refundUseCase: RefundUseCase,
) {
    @PostMapping
    @Operation(summary = "환불 요청 + PG 환불 + 상태 갱신")
    fun refund(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody req: RefundRequest,
    ): ResponseEntity<RefundResponse> {
        val refund = refundUseCase.refund(req.toCommand(idempotencyKey))
        return ResponseEntity.status(HttpStatus.CREATED).body(refund.toResponse())
    }
}
