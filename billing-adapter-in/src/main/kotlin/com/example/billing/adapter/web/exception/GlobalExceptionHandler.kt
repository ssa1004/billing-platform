package com.example.billing.adapter.web.exception

import com.example.billing.adapter.web.dto.ErrorResponse
import com.example.billing.application.exception.BudgetAlertRuleNotFoundException
import com.example.billing.application.exception.InvoiceNotFoundException
import com.example.billing.application.exception.OrderNotFoundException
import com.example.billing.application.exception.PaymentNotFoundException
import com.example.billing.application.exception.RefundNotFoundException
import com.example.billing.application.exception.WalletNotFoundException
import com.example.billing.application.exception.WebhookDeliveryNotFoundException
import com.example.billing.application.exception.WebhookEndpointNotFoundException
import com.example.billing.application.port.out.IdempotencyKeyStore
import com.example.billing.domain.order.IllegalOrderTransitionException
import com.example.billing.domain.wallet.InsufficientBalanceException
import io.github.resilience4j.bulkhead.BulkheadFullException
import io.micrometer.tracing.Tracer
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
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
        InvoiceNotFoundException::class,
        BudgetAlertRuleNotFoundException::class,
        WebhookEndpointNotFoundException::class,
        WebhookDeliveryNotFoundException::class,
    )
    fun handleNotFound(e: RuntimeException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.NOT_FOUND, "NOT_FOUND", e.message ?: "not found")

    @ExceptionHandler(IdempotencyKeyStore.DuplicateRequestException::class)
    fun handleDuplicate(e: IdempotencyKeyStore.DuplicateRequestException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", e.message ?: "duplicate request")

    /**
     * BOLA (OWASP API1) / Function-level Auth (API5) 거절 — Spring Security 의 표준
     * [AccessDeniedException]. `@PreAuthorize` 와 [Caller.requireOwnerOrAdmin] 양쪽에서 던짐.
     *
     * 응답에 reason 을 자세히 노출 하지 않음 — "어떤 customerId 가 다른 customerId 의 자원이다"
     * 같은 정보를 그대로 흘리면 enumeration 에 단서를 줌. detail 은 서버 로그 / trace 로만.
     */
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(e: AccessDeniedException): ResponseEntity<ErrorResponse> {
        log.warn("access denied: {}", e.message)
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "access denied")
    }

    /**
     * 같은 idempotency 키로 다른 body 가 들어온 경우 — client bug. 422 로 즉시 알려서 같은 키
     * 재사용하지 말고 새 키 발급하라고 신호 (결제 API 표준 메시지: "Idempotency-Key already used
     * with different parameters"). ADR-0028 참고.
     */
    @ExceptionHandler(IdempotencyKeyStore.IncompatibleRequestException::class)
    fun handleIncompatible(e: IdempotencyKeyStore.IncompatibleRequestException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.UNPROCESSABLE_ENTITY, "INCOMPATIBLE_PARAMS",
            e.message ?: "idempotency key reused with different request body")

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

    /**
     * 외부 호출 격리 풀 (Bulkhead) 가 가득 차서 호출이 거부된 경우 — 503 + Retry-After.
     *
     * <p>Bulkhead 가 가득 찼다 = 우리 시스템이 외부 종속 (PG 등) 의 슬로우다운으로 한계까지
     * 호출을 발사 중이라는 의미. 클라이언트는 짧은 backoff 후 재시도하면 OK. ADR-0026 참고.</p>
     */
    @ExceptionHandler(BulkheadFullException::class)
    fun handleBulkheadFull(e: BulkheadFullException): ResponseEntity<ErrorResponse> {
        val traceId = tracer.currentSpan()?.context()?.traceId()
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "1")  // 1초 후 retry 권장
            .body(ErrorResponse("BULKHEAD_FULL", e.message ?: "service busy, retry later", emptyList(), traceId))
    }

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
