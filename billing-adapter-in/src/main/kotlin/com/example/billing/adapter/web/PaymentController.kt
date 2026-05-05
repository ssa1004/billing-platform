package com.example.billing.adapter.web

import com.example.billing.adapter.web.dto.PaymentResponse
import com.example.billing.adapter.web.dto.ProcessPaymentRequest
import com.example.billing.application.port.`in`.ProcessPaymentUseCase
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
@RequestMapping("/api/v1/payments")
@Tag(name = "payments", description = "결제 처리")
class PaymentController(
    private val processPayment: ProcessPaymentUseCase,
) {
    @PostMapping
    @Operation(summary = "결제 처리 (PG 호출 + Order 상태 천이)")
    fun process(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody req: ProcessPaymentRequest,
    ): ResponseEntity<PaymentResponse> {
        val payment = processPayment.process(req.toCommand(idempotencyKey))
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentResponse.from(payment))
    }
}
