package com.example.billing.adapter.web

import com.example.billing.adapter.web.auth.Caller
import com.example.billing.adapter.web.dto.OrderResponse
import com.example.billing.adapter.web.dto.PlaceOrderRequest
import com.example.billing.application.port.`in`.PlaceOrderUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "orders", description = "주문 라이프사이클")
class OrderController(
    private val placeOrder: PlaceOrderUseCase,
) {
    @PostMapping
    @Operation(summary = "주문 생성")
    fun place(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody req: PlaceOrderRequest,
    ): ResponseEntity<OrderResponse> {
        val caller = Caller.from(jwt)
        val order = placeOrder.place(req.toCommand(idempotencyKey, caller.owner))
        return ResponseEntity
            .created(URI.create("/api/v1/orders/${order.id()}"))
            .body(OrderResponse.from(order))
    }
}
