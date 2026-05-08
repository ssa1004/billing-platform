package com.example.billing.adapter.web.exception

import com.example.billing.adapter.web.dto.ErrorResponse
import com.example.billing.application.exception.OrderNotFoundException
import com.example.billing.application.exception.PaymentNotFoundException
import com.example.billing.application.exception.RefundNotFoundException
import com.example.billing.application.exception.WalletNotFoundException
import com.example.billing.application.port.out.IdempotencyKeyStore
import com.example.billing.domain.order.IllegalOrderTransitionException
import com.example.billing.domain.wallet.InsufficientBalanceException
import io.micrometer.tracing.Tracer
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class GlobalExceptionHandler(private val tracer: Tracer) {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(
        OrderNotFoundException::class,
        PaymentNotFoundException::class,
        RefundNotFoundException::class,
        WalletNotFoundException::class,
    )
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

    /**
     * 잘못된 JSON 본문 (parse 실패, 타입 mismatch 등) — 400 Bad Request.
     * 미처리 시 500 으로 떨어져 클라이언트가 원인을 파악하기 어려움.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMalformedBody(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "malformed request body")

    /**
     * @RequestParam / @PathVariable 의 type 변환 실패 (e.g. UUID 형식 오류) — 400 Bad Request.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.BAD_REQUEST, "TYPE_MISMATCH", "invalid value for parameter: ${e.name}")

    /**
     * 낙관적 락 충돌 — 동시 수정 race. 클라이언트 retry 가 적절 (멱등 키와 함께 다시 호출).
     * 500 보다는 409 Conflict 가 의미를 더 정확히 전달.
     */
    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleOptimisticLock(e: OptimisticLockingFailureException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "concurrent modification, retry")

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
