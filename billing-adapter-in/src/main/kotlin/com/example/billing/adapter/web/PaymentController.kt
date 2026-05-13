package com.example.billing.adapter.web

import com.example.billing.adapter.web.auth.Caller
import com.example.billing.adapter.web.dto.PaymentResponse
import com.example.billing.adapter.web.dto.ProcessPaymentRequest
import com.example.billing.adapter.web.dto.toResponse
import com.example.billing.application.exception.OrderNotFoundException
import com.example.billing.application.port.`in`.ProcessPaymentUseCase
import com.example.billing.application.port.out.OrderRepository
import com.example.billing.domain.order.OrderId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.jvm.optionals.getOrNull

/**
 * 결제 처리 API.
 *
 * <p><b>OWASP API1 BOLA + API6 Sensitive Flow</b>: 결제 대상 Order 가 caller 의 것이거나
 * admin 일 때만 결제 처리. Order.buyerId 가 caller.owner 와 일치해야 한다.
 * 누구든 다른 사람의 Order 를 결제 시도하는 사고 방지 — 비록 PG 가 거절하더라도 우리 쪽 PENDING
 * Payment row 가 박혀 reconciler 가 흔들리는 비용이 큼.</p>
 */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "payments", description = "결제 처리")
class PaymentController(
    private val processPayment: ProcessPaymentUseCase,
    private val orders: OrderRepository,
) {
    @PostMapping
    @Operation(summary = "결제 처리 (PG 호출 + Order 상태 천이)")
    fun process(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody req: ProcessPaymentRequest,
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<PaymentResponse> {
        val orderId = OrderId.of(req.orderId)
        val order = orders.findById(orderId).getOrNull()
            ?: throw OrderNotFoundException(orderId)
        Caller.from(jwt).requireOwnerOrAdmin(order.buyerId())

        val payment = processPayment.process(req.toCommand(idempotencyKey))
        return ResponseEntity.status(HttpStatus.CREATED).body(payment.toResponse())
    }
}
