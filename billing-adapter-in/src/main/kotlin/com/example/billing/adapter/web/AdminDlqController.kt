package com.example.billing.adapter.web

import com.example.billing.adapter.web.dto.DlqBulkDiscardRequest
import com.example.billing.adapter.web.dto.DlqBulkJobResponse
import com.example.billing.adapter.web.dto.DlqBulkRequest
import com.example.billing.adapter.web.dto.DlqBulkResultResponse
import com.example.billing.adapter.web.dto.DlqDiscardRequest
import com.example.billing.adapter.web.dto.DlqListResponse
import com.example.billing.adapter.web.dto.DlqMessageDetailResponse
import com.example.billing.adapter.web.dto.DlqMessageResponse
import com.example.billing.application.dto.DlqMessageFilter
import com.example.billing.application.dto.DlqSource
import com.example.billing.application.port.`in`.DlqAdminUseCase
import com.example.billing.application.port.`in`.DlqBulkAdminUseCase
import com.example.billing.application.port.out.AdminRateLimiter
import com.example.billing.domain.audit.AuditActor
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * DLQ (`.DLT` 메시지) 운영 endpoint — ADR-0033 (notification-hub ADR-0015 패턴 이식).
 *
 * **권한**: class 단위 `@PreAuthorize("hasRole('admin')")` — 모든 endpoint ADMIN 만.
 *
 * **rate limit**: 호출자 IP × scope (`admin:dlq:read|write|bulk`) 별 token bucket. 기본 분당
 * 60. 초과 시 429 + Retry-After.
 *
 * **audit**: 모든 write endpoint (replay / discard / bulk-*) 가 [com.example.billing.domain.audit.AuditAction]
 * DLQ_* 발행 — actor / targetId (messageId 또는 bulk-{op}) / reason / customerId 기록.
 *
 * **billing 특유 — 돈 직결**:
 * - bulk-replay default = dry-run. `confirm=true` 가 있어야 실 실행 (의도치 않은 재청구 방지).
 * - 단건 replay 두 번째 호출 시 409 `ILLEGAL_DLQ_OPERATION` (멱등성 가드).
 * - DELETE `/{messageId}` 차단 (405) — soft discard 만 허용.
 *
 * **호환**: 기존 [DlqAdminController] (`/admin/dlq/replay`) 는 그대로 유지. 새 endpoint 만 추가.
 *
 * **path / query 컨벤션**: `messageId` 는 `<dltTopic>:<partition>:<offset>` 합성 문자열.
 * 콜론 (`:`) 는 RFC 3986 의 path segment 에서 그대로 허용되어 별도 인코딩 불필요.
 */
@RestController
@RequestMapping("/api/v1/admin/dlq")
@Tag(name = "admin-dlq", description = "DLQ 운영 — list / detail / replay / discard / bulk / stats")
@PreAuthorize("hasRole('admin')")
class AdminDlqController(
    private val useCase: DlqAdminUseCase,
    private val bulkUseCase: DlqBulkAdminUseCase,
    private val adminRateLimiter: AdminRateLimiter,
) {

    @GetMapping
    @Operation(summary = "DLQ list — filter + cursor 페이지네이션")
    fun search(
        @RequestParam(required = false) source: DlqSource?,
        @RequestParam(required = false) topic: String?,
        @RequestParam(required = false) consumerGroup: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) errorType: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") size: Int,
        request: HttpServletRequest,
    ): ResponseEntity<DlqListResponse> {
        rateLimit(SCOPE_READ, request)
        val filter = DlqMessageFilter(source, topic, consumerGroup, from, to, errorType)
        val page = useCase.search(filter, cursor, size)
        return ResponseEntity.ok(DlqListResponse.from(page))
    }

    @GetMapping("/{messageId}")
    @Operation(summary = "DLQ 단건 상세 — payload / headers / stacktrace")
    fun detail(
        @PathVariable messageId: String,
        request: HttpServletRequest,
    ): ResponseEntity<DlqMessageDetailResponse> {
        rateLimit(SCOPE_READ, request)
        val detail = useCase.detail(messageId).orElse(null)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(DlqMessageDetailResponse.from(detail))
    }

    @PostMapping("/{messageId}/replay")
    @Operation(summary = "DLQ 단건 replay — 원본 topic 으로 재발행 (Idempotency-Key 복사)")
    fun replay(
        @PathVariable messageId: String,
        @AuthenticationPrincipal jwt: Jwt?,
        request: HttpServletRequest,
    ): ResponseEntity<DlqMessageResponse> {
        rateLimit(SCOPE_WRITE, request)
        val actor = toActor(jwt, request)
        val replayed = useCase.replay(messageId, actor)
        return ResponseEntity.ok(DlqMessageResponse.from(replayed))
    }

    @PostMapping("/{messageId}/discard")
    @Operation(summary = "DLQ 단건 discard — 영구 종료 (soft, reason 필수)")
    fun discard(
        @PathVariable messageId: String,
        @Valid @RequestBody body: DlqDiscardRequest,
        @AuthenticationPrincipal jwt: Jwt?,
        request: HttpServletRequest,
    ): ResponseEntity<DlqMessageResponse> {
        rateLimit(SCOPE_WRITE, request)
        val actor = toActor(jwt, request)
        val discarded = useCase.discard(messageId, body.reason, actor)
        return ResponseEntity.ok(DlqMessageResponse.from(discarded))
    }

    @PostMapping("/bulk-replay")
    @Operation(summary = "DLQ bulk replay — confirm=true 가 없으면 dry-run 강제")
    fun bulkReplay(
        @Valid @RequestBody body: DlqBulkRequest,
        @AuthenticationPrincipal jwt: Jwt?,
        request: HttpServletRequest,
    ): ResponseEntity<DlqBulkResultResponse> {
        rateLimit(SCOPE_BULK, request)
        val actor = toActor(jwt, request)
        val result = bulkUseCase.bulkReplay(
            filter = body.toFilter(),
            confirm = body.confirmedOrDefault(),
            reason = body.reason,
            actor = actor,
        )
        return ResponseEntity.ok(DlqBulkResultResponse.from(result))
    }

    @PostMapping("/bulk-discard")
    @Operation(summary = "DLQ bulk discard — reason 필수, confirm=true 가 없으면 dry-run 강제")
    fun bulkDiscard(
        @Valid @RequestBody body: DlqBulkDiscardRequest,
        @AuthenticationPrincipal jwt: Jwt?,
        request: HttpServletRequest,
    ): ResponseEntity<DlqBulkResultResponse> {
        rateLimit(SCOPE_BULK, request)
        val actor = toActor(jwt, request)
        val result = bulkUseCase.bulkDiscard(
            filter = body.toFilter(),
            confirm = body.confirmedOrDefault(),
            reason = body.reason,
            actor = actor,
        )
        return ResponseEntity.ok(DlqBulkResultResponse.from(result))
    }

    @GetMapping("/bulk-jobs/{jobId}")
    @Operation(summary = "DLQ bulk job 진행도 / 결과 조회")
    fun bulkJob(
        @PathVariable jobId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<DlqBulkJobResponse> {
        rateLimit(SCOPE_READ, request)
        val job = bulkUseCase.getBulkJob(jobId).orElse(null)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(DlqBulkJobResponse.from(job))
    }

    @GetMapping("/stats")
    @Operation(summary = "DLQ 시간 bucket × source × errorClass × customer 통계")
    fun stats(
        @RequestParam(required = false) source: DlqSource?,
        @RequestParam(required = false) topic: String?,
        @RequestParam(required = false) consumerGroup: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) bucket: Duration?,
        @RequestParam(required = false) errorType: String?,
        request: HttpServletRequest,
    ): ResponseEntity<com.example.billing.adapter.web.dto.DlqStatsResponse> {
        rateLimit(SCOPE_READ, request)
        val filter = DlqMessageFilter(source, topic, consumerGroup, from, to, errorType)
        val stats = useCase.stats(filter, from, to, bucket)
        return ResponseEntity.ok(com.example.billing.adapter.web.dto.DlqStatsResponse.from(stats))
    }

    /**
     * audit 누락 / 잘못된 호출을 막기 위해 explicit DELETE 는 노출 X — discard 가 soft-delete 역할.
     * 405 로 명확하게 안내. notification-hub ADR-0015 의 동일 가드.
     */
    @DeleteMapping("/{messageId}")
    fun hardDeleteNotAllowed(
        @PathVariable messageId: String,
        request: HttpServletRequest,
    ) {
        rateLimit(SCOPE_WRITE, request)
        throw UnsupportedOperationException(
            "hard delete not supported — use POST /discard for soft delete with reason",
        )
    }

    /**
     * 호출자 IP + scope 기준 admin rate limit. 초과 시 [com.example.billing.application.exception.RateLimitExceededException]
     * (GlobalExceptionHandler 에서 429).
     *
     * IP 추출은 X-Forwarded-For 우선 (LB 뒤). 첫번째 토큰 사용.
     */
    private fun rateLimit(scope: String, request: HttpServletRequest) {
        val callerKey = clientKey(request)
        val decision = adminRateLimiter.tryConsume(scope, callerKey)
        if (!decision.allowed) {
            throw com.example.billing.application.exception.RateLimitExceededException(
                scope,
                decision.retryAfterMillis,
            )
        }
    }

    private fun toActor(jwt: Jwt?, request: HttpServletRequest): AuditActor {
        val operatorId = jwt?.subject?.takeIf(String::isNotBlank) ?: "anonymous"
        val ip = clientKey(request)
        val userAgent = request.getHeader("User-Agent")
        return AuditActor.operator(operatorId, ip, userAgent)
    }

    private fun clientKey(request: HttpServletRequest): String {
        val xff = request.getHeader("X-Forwarded-For")
        if (!xff.isNullOrBlank()) {
            val comma = xff.indexOf(',')
            return if (comma < 0) xff.trim() else xff.substring(0, comma).trim()
        }
        return request.remoteAddr?.takeIf(String::isNotBlank) ?: "unknown"
    }

    companion object {
        const val SCOPE_READ: String = "admin:dlq:read"
        const val SCOPE_WRITE: String = "admin:dlq:write"
        const val SCOPE_BULK: String = "admin:dlq:bulk"
    }
}
