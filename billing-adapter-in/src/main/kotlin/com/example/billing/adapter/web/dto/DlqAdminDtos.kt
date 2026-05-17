package com.example.billing.adapter.web.dto

import com.example.billing.application.dto.DlqBulkJob
import com.example.billing.application.dto.DlqBulkResult
import com.example.billing.application.dto.DlqListPage
import com.example.billing.application.dto.DlqMessageDetail
import com.example.billing.application.dto.DlqMessageFilter
import com.example.billing.application.dto.DlqMessageView
import com.example.billing.application.dto.DlqSource
import com.example.billing.application.dto.DlqStats
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/**
 * DLQ admin REST DTO 모음.
 *
 * 도메인/application DTO 와 1:1 매핑되는 얇은 wrapper — Jackson serialization 만 위한 표면.
 * `messageId` 는 `<dltTopic>:<partition>:<offset>` 합성 문자열 (use case 와 동일 의미).
 */

data class DlqMessageResponse(
    val messageId: String,
    val source: String,
    val dltTopic: String,
    val originalTopic: String,
    val partition: Int,
    val offset: Long,
    val key: String?,
    val errorClass: String?,
    val failureReason: String?,
    val occurredAt: Instant,
    val payloadLength: Int,
) {
    companion object {
        fun from(v: DlqMessageView): DlqMessageResponse = DlqMessageResponse(
            messageId = v.messageId,
            source = v.source,
            dltTopic = v.dltTopic,
            originalTopic = v.originalTopic,
            partition = v.partition,
            offset = v.offset,
            key = v.key,
            errorClass = v.errorClass,
            failureReason = v.failureReason,
            occurredAt = v.occurredAt,
            payloadLength = v.payloadLength,
        )
    }
}

data class DlqListResponse(
    val items: List<DlqMessageResponse>,
    val nextCursor: String?,
    val size: Int,
) {
    companion object {
        fun from(p: DlqListPage): DlqListResponse = DlqListResponse(
            items = p.items.map(DlqMessageResponse::from),
            nextCursor = p.nextCursor,
            size = p.size,
        )
    }
}

data class DlqMessageDetailResponse(
    val messageId: String,
    val source: String,
    val dltTopic: String,
    val originalTopic: String,
    val partition: Int,
    val offset: Long,
    val key: String?,
    val payload: String,
    val payloadLength: Int,
    val headers: Map<String, String>,
    val errorClass: String?,
    val failureReason: String?,
    val originalStacktrace: String?,
    val originalConsumerGroup: String?,
    val originalTimestamp: Instant?,
    val occurredAt: Instant,
    val retryCount: Int,
    val idempotencyKey: String?,
    val customerId: String?,
) {
    companion object {
        fun from(d: DlqMessageDetail): DlqMessageDetailResponse = DlqMessageDetailResponse(
            messageId = d.messageId,
            source = d.source,
            dltTopic = d.dltTopic,
            originalTopic = d.originalTopic,
            partition = d.partition,
            offset = d.offset,
            key = d.key,
            payload = d.payload,
            payloadLength = d.payloadLength,
            headers = d.headers,
            errorClass = d.errorClass,
            failureReason = d.failureReason,
            originalStacktrace = d.originalStacktrace,
            originalConsumerGroup = d.originalConsumerGroup,
            originalTimestamp = d.originalTimestamp,
            occurredAt = d.occurredAt,
            retryCount = d.retryCount,
            idempotencyKey = d.idempotencyKey,
            customerId = d.customerId,
        )
    }
}

data class DlqStatsResponse(
    val from: Instant,
    val to: Instant,
    val bucketDuration: String,
    val totalCount: Long,
    val byBucket: List<BucketCountResponse>,
    val bySource: List<KeyedCountResponse>,
    val byErrorClass: List<KeyedCountResponse>,
    val byCustomer: List<KeyedCountResponse>,
) {
    data class BucketCountResponse(val bucketStart: Instant, val count: Long)
    data class KeyedCountResponse(val key: String, val count: Long)

    companion object {
        fun from(s: DlqStats): DlqStatsResponse = DlqStatsResponse(
            from = s.from,
            to = s.to,
            bucketDuration = s.bucketDuration.toString(),
            totalCount = s.totalCount,
            byBucket = s.byBucket.map { BucketCountResponse(it.bucketStart, it.count) },
            bySource = s.bySource.map { KeyedCountResponse(it.key, it.count) },
            byErrorClass = s.byErrorClass.map { KeyedCountResponse(it.key, it.count) },
            byCustomer = s.byCustomer.map { KeyedCountResponse(it.key, it.count) },
        )
    }
}

/**
 * bulk-replay / bulk-discard 의 공통 request body.
 *
 * [confirm] default = false → 항상 dry-run. 운영자가 명시적으로 true 줘야 실 실행 — billing 의
 * "돈 직결" 안전망 (ADR-0033).
 */
data class DlqBulkRequest(
    val source: DlqSource? = null,
    val topic: String? = null,
    val consumerGroup: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    @field:Size(max = 256) val errorType: String? = null,
    val confirm: Boolean? = null,
    @field:Size(max = 256) val reason: String? = null,
) {
    fun toFilter(): DlqMessageFilter =
        DlqMessageFilter(source, topic, consumerGroup, from, to, errorType)

    fun confirmedOrDefault(): Boolean = confirm == true
}

/** bulk-discard 는 reason 필수 — 따로 record 로 [NotBlank]. */
data class DlqBulkDiscardRequest(
    val source: DlqSource? = null,
    val topic: String? = null,
    val consumerGroup: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    @field:Size(max = 256) val errorType: String? = null,
    val confirm: Boolean? = null,
    @field:NotBlank @field:Size(max = 256) val reason: String = "",
) {
    fun toFilter(): DlqMessageFilter =
        DlqMessageFilter(source, topic, consumerGroup, from, to, errorType)

    fun confirmedOrDefault(): Boolean = confirm == true
}

data class DlqBulkResultResponse(
    val mode: String,
    val estimatedCount: Long,
    val sampleMessageIds: List<String>,
    val jobId: UUID?,
    val startedAt: Instant?,
) {
    companion object {
        fun from(r: DlqBulkResult): DlqBulkResultResponse = DlqBulkResultResponse(
            mode = r.mode.name,
            estimatedCount = r.estimatedCount,
            sampleMessageIds = r.sampleMessageIds,
            jobId = r.jobId,
            startedAt = r.startedAt,
        )
    }
}

data class DlqBulkJobResponse(
    val jobId: UUID,
    val operation: String,
    val state: String,
    val totalCount: Long,
    val processedCount: Long,
    val successCount: Long,
    val failureCount: Long,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val firstError: String?,
) {
    companion object {
        fun from(j: DlqBulkJob): DlqBulkJobResponse = DlqBulkJobResponse(
            jobId = j.jobId,
            operation = j.operation.name,
            state = j.state.name,
            totalCount = j.totalCount,
            processedCount = j.processedCount,
            successCount = j.successCount,
            failureCount = j.failureCount,
            startedAt = j.startedAt,
            finishedAt = j.finishedAt,
            firstError = j.firstError,
        )
    }
}

/** 단건 discard 의 body — 빈 body 도 허용 (reason null) 하지만 controller 에서 NotBlank 강제. */
data class DlqDiscardRequest(
    @field:NotBlank @field:Size(max = 256) val reason: String = "",
)
