package com.example.wallet.adapter.web.exception

import com.example.wallet.adapter.web.dto.ErrorResponse
import com.example.wallet.application.exception.OrderNotFoundException
import com.example.wallet.application.exception.PaymentNotFoundException
import com.example.wallet.application.exception.WalletNotFoundException
import com.example.wallet.application.port.out.IdempotencyKeyStore
import com.example.wallet.domain.order.IllegalOrderTransitionException
import com.example.wallet.domain.wallet.InsufficientBalanceException
import io.micrometer.tracing.Tracer
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler(private val tracer: Tracer) {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(OrderNotFoundException::class, PaymentNotFoundException::class, WalletNotFoundException::class)
    fun handleNotFound(e: RuntimeException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.NOT_FOUND, "NOT_FOUND", e.message ?: "not found")

    @ExceptionHandler(IdempotencyKeyStore.DuplicateRequestException::class)
    fun handleDuplicate(e: IdempotencyKeyStore.DuplicateRequestException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", e.message ?: "duplicate request")

    @ExceptionHandler(InsufficientBalanceException::class)
    fun handleInsufficient(e: InsufficientBalanceException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_BALANCE", e.message ?: "insufficient balance")

    @ExceptionHandler(IllegalOrderTransitionException::class)
    fun handleIllegalTransition(e: IllegalOrderTransitionException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.CONFLICT, "ILLEGAL_STATE", e.message ?: "illegal state")

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(e: IllegalStateException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.CONFLICT, "ILLEGAL_STATE", e.message ?: "illegal state")

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.message ?: "bad request")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val details = e.bindingResult.fieldErrors.map { "${it.field}: ${it.defaultMessage}" }
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request validation failed", details)
    }

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(e: MissingRequestHeaderException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "missing header: ${e.headerName}")

    @ExceptionHandler(Exception::class)
    fun handleAll(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("unhandled", e)
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "internal error")
    }

    private fun build(
        status: HttpStatus,
        code: String,
        message: String,
        details: List<String> = emptyList(),
    ): ResponseEntity<ErrorResponse> {
        val traceId = tracer.currentSpan()?.context()?.traceId()
        return ResponseEntity.status(status).body(ErrorResponse(code, message, details, traceId))
    }
}
