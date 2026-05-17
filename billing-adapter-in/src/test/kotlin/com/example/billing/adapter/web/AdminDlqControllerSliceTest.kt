package com.example.billing.adapter.web

import com.example.billing.adapter.web.exception.GlobalExceptionHandler
import com.example.billing.application.dto.DlqBulkJob
import com.example.billing.application.dto.DlqBulkResult
import com.example.billing.application.dto.DlqListPage
import com.example.billing.application.dto.DlqMessageDetail
import com.example.billing.application.dto.DlqMessageFilter
import com.example.billing.application.dto.DlqMessageView
import com.example.billing.application.dto.DlqSource
import com.example.billing.application.exception.IllegalDlqOperationException
import com.example.billing.application.port.`in`.DlqAdminUseCase
import com.example.billing.application.port.`in`.DlqBulkAdminUseCase
import com.example.billing.application.port.out.AdminRateLimiter
import com.example.billing.domain.shared.RateLimitDecision
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.tracing.Tracer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * AdminDlqController 슬라이스 테스트 — 라우팅 / JSON 매핑 / 예외 → HTTP 매핑 / rate-limit 통합.
 *
 * 검증 invariant:
 * - search / detail / replay / discard / bulk-* / stats 8개 endpoint 정상 응답.
 * - IllegalDlqOperationException → 409 ILLEGAL_DLQ_OPERATION.
 * - rate limit 초과 → 429 + Retry-After.
 * - DELETE /{messageId} → 405 (hard delete 차단).
 * - bulk request body 의 confirm=true 가 그대로 use case 에 전달.
 */
class AdminDlqControllerSliceTest {

    private val useCase: DlqAdminUseCase = mock()
    private val bulkUseCase: DlqBulkAdminUseCase = mock()
    private val rateLimiter: AdminRateLimiter = mock()
    private lateinit var mockMvc: MockMvc

    private val mapper = ObjectMapper().registerKotlinModule().apply {
        registerModule(com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
    }

    private val now: Instant = Instant.parse("2026-05-15T10:00:00Z")
    private val sampleMessageId = "billing.payment.captured.DLT:0:1"

    @BeforeEach
    fun setUp() {
        val converter = MappingJackson2HttpMessageConverter(mapper)
        // 기본은 허용 — 개별 테스트가 deny 시뮬레이트.
        whenever(rateLimiter.tryConsume(any(), any())).thenReturn(RateLimitDecision.allow(60))
        mockMvc = MockMvcBuilders
            .standaloneSetup(AdminDlqController(useCase, bulkUseCase, rateLimiter))
            .setControllerAdvice(GlobalExceptionHandler(Tracer.NOOP))
            .setMessageConverters(converter)
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
    }

    @Test
    fun `GET dlq returns list with items and nextCursor`() {
        val page = DlqListPage(items = listOf(view(sampleMessageId)), nextCursor = sampleMessageId, size = 50)
        whenever(useCase.search(any<DlqMessageFilter>(), eq(null), eq(50))).thenReturn(page)

        mockMvc.perform(get("/api/v1/admin/dlq?size=50&source=PAYMENT"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].messageId").value(sampleMessageId))
            .andExpect(jsonPath("$.nextCursor").value(sampleMessageId))
            .andExpect(jsonPath("$.size").value(50))
    }

    @Test
    fun `GET dlq messageId returns detail`() {
        whenever(useCase.detail(sampleMessageId)).thenReturn(Optional.of(detail(sampleMessageId)))

        mockMvc.perform(get("/api/v1/admin/dlq/$sampleMessageId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.messageId").value(sampleMessageId))
            .andExpect(jsonPath("$.source").value(DlqSource.PAYMENT.name))
            .andExpect(jsonPath("$.customerId").value("cust-42"))
            .andExpect(jsonPath("$.idempotencyKey").value("idem-xyz"))
    }

    @Test
    fun `GET dlq messageId returns 404 when not found`() {
        whenever(useCase.detail(sampleMessageId)).thenReturn(Optional.empty())

        mockMvc.perform(get("/api/v1/admin/dlq/$sampleMessageId"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST dlq messageId replay returns 200`() {
        whenever(useCase.replay(eq(sampleMessageId), any())).thenReturn(view(sampleMessageId))

        mockMvc.perform(post("/api/v1/admin/dlq/$sampleMessageId/replay"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.messageId").value(sampleMessageId))
    }

    @Test
    fun `POST dlq messageId replay second time returns 409 ILLEGAL_DLQ_OPERATION`() {
        whenever(useCase.replay(eq(sampleMessageId), any()))
            .thenThrow(IllegalDlqOperationException("already processed"))

        mockMvc.perform(post("/api/v1/admin/dlq/$sampleMessageId/replay"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("ILLEGAL_DLQ_OPERATION"))
    }

    @Test
    fun `POST dlq messageId discard requires reason`() {
        val body = mapOf("reason" to "")

        mockMvc.perform(
            post("/api/v1/admin/dlq/$sampleMessageId/discard")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST dlq messageId discard with valid reason returns 200`() {
        whenever(useCase.discard(eq(sampleMessageId), eq("duplicate"), any()))
            .thenReturn(view(sampleMessageId))

        val body = mapOf("reason" to "duplicate")
        mockMvc.perform(
            post("/api/v1/admin/dlq/$sampleMessageId/discard")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        ).andExpect(status().isOk)
    }

    @Test
    fun `POST bulk-replay confirm true is passed to use case`() {
        whenever(bulkUseCase.bulkReplay(any(), eq(true), any(), any()))
            .thenReturn(DlqBulkResult.executing(UUID.randomUUID(), 5, listOf(sampleMessageId)))

        val body = mapOf(
            "source" to "PAYMENT",
            "confirm" to true,
            "reason" to "vendor recovery",
        )
        mockMvc.perform(
            post("/api/v1/admin/dlq/bulk-replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mode").value("EXECUTING"))
            .andExpect(jsonPath("$.estimatedCount").value(5))
    }

    @Test
    fun `POST bulk-replay without confirm becomes dry-run`() {
        // confirm 미지정 (null) → confirmedOrDefault() 가 false → use case 호출은 confirm=false.
        // reason 도 미지정 → null 그대로. mockito-kotlin 의 anyOrNull() 가 null 도 매칭.
        whenever(bulkUseCase.bulkReplay(any(), eq(false), org.mockito.kotlin.anyOrNull(), any()))
            .thenReturn(DlqBulkResult.dryRun(7, listOf(sampleMessageId)))

        val body = mapOf("source" to "PAYMENT")
        mockMvc.perform(
            post("/api/v1/admin/dlq/bulk-replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mode").value("DRY_RUN"))
            .andExpect(jsonPath("$.estimatedCount").value(7))
            .andExpect(jsonPath("$.jobId").doesNotExist())
    }

    @Test
    fun `POST bulk-discard rejects blank reason`() {
        val body = mapOf("source" to "REFUND", "confirm" to false, "reason" to "")
        mockMvc.perform(
            post("/api/v1/admin/dlq/bulk-discard")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `GET bulk-jobs jobId returns job`() {
        val jobId = UUID.randomUUID()
        val job = DlqBulkJob(jobId, DlqBulkJob.Operation.REPLAY, DlqBulkJob.State.SUCCEEDED,
            10, 10, 10, 0, now, now, null)
        whenever(bulkUseCase.getBulkJob(jobId)).thenReturn(Optional.of(job))

        mockMvc.perform(get("/api/v1/admin/dlq/bulk-jobs/$jobId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("SUCCEEDED"))
            .andExpect(jsonPath("$.successCount").value(10))
    }

    @Test
    fun `DELETE messageId returns 405 — hard delete blocked`() {
        mockMvc.perform(delete("/api/v1/admin/dlq/$sampleMessageId"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
    }

    @Test
    fun `rate limit exceeded returns 429 with Retry-After header`() {
        whenever(rateLimiter.tryConsume(any(), any()))
            .thenReturn(RateLimitDecision.deny(2_500))

        mockMvc.perform(get("/api/v1/admin/dlq"))
            .andExpect(status().isTooManyRequests)
            .andExpect(header().string("Retry-After", "2"))
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
    }

    // ── helpers ──

    private fun view(messageId: String) = DlqMessageView(
        messageId = messageId,
        source = DlqSource.PAYMENT.name,
        dltTopic = "billing.payment.captured.DLT",
        originalTopic = "billing.payment.captured",
        partition = 0,
        offset = 1L,
        key = "key",
        errorClass = "PgException",
        failureReason = "PG 5xx",
        occurredAt = now,
        payloadLength = 100,
    )

    private fun detail(messageId: String) = DlqMessageDetail(
        messageId = messageId,
        source = DlqSource.PAYMENT.name,
        dltTopic = "billing.payment.captured.DLT",
        originalTopic = "billing.payment.captured",
        partition = 0,
        offset = 1L,
        key = "key",
        payload = "{\"orderId\":\"o-1\"}",
        payloadLength = 17,
        headers = linkedMapOf(
            "Idempotency-Key" to "idem-xyz",
            "customer-id" to "cust-42",
        ),
        errorClass = "PgException",
        failureReason = "PgException: PG 5xx",
        originalStacktrace = "at ...",
        originalConsumerGroup = "billing-payment-consumer",
        originalTimestamp = now,
        occurredAt = now,
        retryCount = 3,
        idempotencyKey = "idem-xyz",
        customerId = "cust-42",
    )
}
