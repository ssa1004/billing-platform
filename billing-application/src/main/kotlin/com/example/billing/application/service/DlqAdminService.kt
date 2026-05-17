package com.example.billing.application.service

import com.example.billing.application.dto.DlqListPage
import com.example.billing.application.dto.DlqMessageDetail
import com.example.billing.application.dto.DlqMessageFilter
import com.example.billing.application.dto.DlqMessageView
import com.example.billing.application.dto.DlqStats
import com.example.billing.application.exception.IllegalDlqOperationException
import com.example.billing.application.port.`in`.AuditLogger
import com.example.billing.application.port.`in`.DlqAdminUseCase
import com.example.billing.application.port.out.DlqMessageStore
import com.example.billing.domain.audit.AuditAction
import com.example.billing.domain.audit.AuditActor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.Optional

/**
 * DLQ 단건 운영. 권한 가드는 controller 의 `@PreAuthorize("hasRole('admin')")` 가 책임 —
 * service 는 audit / dry-run / 도메인 가드만 다룬다.
 *
 * - search / detail / stats: read-only. cursor 페이지네이션, size cap.
 * - replay: 원본 topic 으로 재발행. 이미 처리된 메시지면 [IllegalDlqOperationException].
 * - discard: 영구 종료 (soft). [reason] 필수. hard DELETE 차단.
 *
 * **billing 특유 — 돈 직결 audit**: replay / discard 의 `targetType` = `DlqMessage`,
 * `targetId` = messageId. `after_json` 에 `originalTopic` / `customerId` (있을 때) 까지 기록 →
 * 분쟁 발생 시 어떤 customer 의 어떤 결제 / 환불이 재발행됐는지 즉답.
 */
@Service
open class DlqAdminService(
    private val store: DlqMessageStore,
    private val auditLogger: AuditLogger,
) : DlqAdminUseCase {

    @Transactional(readOnly = true)
    override fun search(filter: DlqMessageFilter, cursor: String?, size: Int): DlqListPage {
        val safeSize = clampPageSize(size)
        if (filter.isUnknownConsumerGroup()) {
            return DlqListPage(emptyList(), null, safeSize)
        }
        val items = store.search(filter, cursor, safeSize)
        // 결과가 safeSize 와 같으면 다음 페이지 가능성 — 마지막 messageId 를 cursor 로. 더 적으면 끝.
        val nextCursor = if (items.size == safeSize) items.last().messageId else null
        return DlqListPage(items, nextCursor, safeSize)
    }

    @Transactional(readOnly = true)
    override fun detail(messageId: String): Optional<DlqMessageDetail> =
        store.findDetail(messageId)

    @Transactional
    override fun replay(messageId: String, actor: AuditActor): DlqMessageView {
        val replayed = try {
            store.replay(messageId)
        } catch (e: IllegalDlqOperationException) {
            // 멱등성 가드 — 두 번째 호출은 audit 없이 그대로 전파 (409). 첫 호출의 audit 는 그대로.
            throw e
        }
        auditLogger.log(
            actor = actor,
            action = AuditAction.DLQ_REPLAY,
            targetType = TARGET_TYPE,
            targetId = messageId,
            beforeJson = headersBefore(replayed),
            afterJson = AuditPayloads.`object`()
                .put("originalTopic", replayed.originalTopic)
                .put("partition", replayed.partition)
                .put("offset", replayed.offset)
                .put("source", replayed.source)
                .put("customerId", replayed.customerId)
                .put("idempotencyKey", replayed.idempotencyKey)
                .build(),
            reason = null,
        )
        log.info(
            "[dlq] replay messageId={} source={} customer={} actor={}",
            messageId, replayed.source, replayed.customerId, actor.id,
        )
        return toView(replayed)
    }

    @Transactional
    override fun discard(messageId: String, reason: String, actor: AuditActor): DlqMessageView {
        require(reason.isNotBlank()) { "reason must not be blank" }
        val discarded = try {
            store.discard(messageId, reason)
        } catch (e: IllegalDlqOperationException) {
            throw e
        }
        auditLogger.log(
            actor = actor,
            action = AuditAction.DLQ_DISCARD,
            targetType = TARGET_TYPE,
            targetId = messageId,
            beforeJson = headersBefore(discarded),
            afterJson = null,
            reason = reason,
        )
        log.info(
            "[dlq] discard messageId={} source={} customer={} actor={} reason={}",
            messageId, discarded.source, discarded.customerId, actor.id, reason,
        )
        return toView(discarded)
    }

    @Transactional(readOnly = true)
    override fun stats(
        filter: DlqMessageFilter,
        from: Instant?,
        to: Instant?,
        bucket: Duration?,
    ): DlqStats {
        val effectiveTo = to ?: Instant.now()
        val effectiveFrom = from ?: effectiveTo.minus(Duration.ofHours(24))
        require(!effectiveFrom.isAfter(effectiveTo)) { "from must be <= to" }
        val effectiveBucket = bucket ?: Duration.ofHours(1)
        require(!effectiveBucket.isZero && !effectiveBucket.isNegative) {
            "bucket must be positive"
        }

        // 어댑터가 (bucketStart, source, errorClass, customerId, count) raw row 를 돌려주면
        // use case 가 차원별 합계를 만든다 — notification-hub ADR-0015 와 같은 분리.
        val rows = store.aggregateStats(filter, effectiveFrom, effectiveTo, effectiveBucket)

        val byBucket = rows.groupBy { it.bucketStart }
            .map { (k, v) -> DlqStats.BucketCount(k, v.sumOf { r -> r.count }) }
            .sortedBy { it.bucketStart }
        val bySource = rows.groupBy { it.source }
            .map { (k, v) -> DlqStats.KeyedCount(k, v.sumOf { r -> r.count }) }
            .sortedByDescending { it.count }
        val byErrorClass = rows.groupBy { it.errorClass ?: "(unknown)" }
            .map { (k, v) -> DlqStats.KeyedCount(k, v.sumOf { r -> r.count }) }
            .sortedByDescending { it.count }
        val byCustomer = rows.filter { !it.customerId.isNullOrBlank() }
            .groupBy { it.customerId!! }
            .map { (k, v) -> DlqStats.KeyedCount(k, v.sumOf { r -> r.count }) }
            .sortedByDescending { it.count }

        val total = rows.sumOf { it.count }
        return DlqStats(
            from = effectiveFrom,
            to = effectiveTo,
            bucketDuration = effectiveBucket,
            totalCount = total,
            byBucket = byBucket,
            bySource = bySource,
            byErrorClass = byErrorClass,
            byCustomer = byCustomer,
        )
    }

    private fun headersBefore(d: DlqMessageDetail): String =
        AuditPayloads.`object`()
            .put("dltTopic", d.dltTopic)
            .put("partition", d.partition)
            .put("offset", d.offset)
            .put("source", d.source)
            .put("key", d.key)
            .put("errorClass", d.errorClass)
            .put("failureReason", d.failureReason)
            .put("customerId", d.customerId)
            .build()

    private fun toView(d: DlqMessageDetail): DlqMessageView = DlqMessageView(
        messageId = d.messageId,
        source = d.source,
        dltTopic = d.dltTopic,
        originalTopic = d.originalTopic,
        partition = d.partition,
        offset = d.offset,
        key = d.key,
        errorClass = d.errorClass,
        failureReason = d.failureReason,
        occurredAt = d.occurredAt,
        payloadLength = d.payloadLength,
    )

    companion object {

        private val log = LoggerFactory.getLogger(DlqAdminService::class.java)

        /** audit 의 targetType. 모든 DLQ 관련 row 는 이 값으로 grouping. */
        const val TARGET_TYPE: String = "DlqMessage"

        /** Controller / search 가 강제하는 page size 상한 — 그 이상은 cursor 로 페이지. */
        const val MAX_PAGE_SIZE: Int = 200

        @JvmStatic
        private fun clampPageSize(requested: Int): Int =
            minOf(maxOf(requested, 1), MAX_PAGE_SIZE)
    }
}
