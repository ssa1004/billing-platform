package com.example.billing.adapter.web

import com.example.billing.adapter.web.auth.Caller
import com.example.billing.adapter.web.dto.RefundRequest
import com.example.billing.adapter.web.dto.RefundResponse
import com.example.billing.adapter.web.dto.toResponse
import com.example.billing.application.exception.OrderNotFoundException
import com.example.billing.application.exception.PaymentNotFoundException
import com.example.billing.application.port.`in`.RefundUseCase
import com.example.billing.application.port.out.OrderRepository
import com.example.billing.application.port.out.PaymentRepository
import com.example.billing.domain.payment.PaymentId
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
 * 환불 처리 API.
 *
 * <p><b>OWASP API1 BOLA + API6 Sensitive Flow</b>: payment → order.buyerId 가 caller 와
 * 일치하거나 admin 일 때만 환불 진행. RefundService 의 Order state transition
 * (PAID → REFUNDED) 가 중복 환불은 막아주지만, "다른 사람 결제를 환불"하는 abuse 는 그
 * 도메인 invariant 이전에 컨트롤러에서 잘라야 한다.</p>
 */
@RestController
@RequestMapping("/api/v1/refunds")
@Tag(name = "refunds", description = "환불 처리")
class RefundController(
    private val refundUseCase: RefundUseCase,
    private val payments: PaymentRepository,
    private val orders: OrderRepository,
) {
    @PostMapping
    @Operation(summary = "환불 요청 + PG 환불 + 상태 갱신")
    fun refund(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody req: RefundRequest,
        @AuthenticationPrincipal jwt: Jwt? = null,
    ): ResponseEntity<RefundResponse> {
        val paymentId = PaymentId.of(req.paymentId)
        val payment = payments.findById(paymentId).getOrNull()
            ?: throw PaymentNotFoundException(paymentId)
        val order = orders.findById(payment.orderId()).getOrNull()
            ?: throw OrderNotFoundException(payment.orderId())
        Caller.from(jwt).requireOwnerOrAdmin(order.buyerId())

        val refund = refundUseCase.refund(req.toCommand(idempotencyKey))
        return ResponseEntity.status(HttpStatus.CREATED).body(refund.toResponse())
    }
}
