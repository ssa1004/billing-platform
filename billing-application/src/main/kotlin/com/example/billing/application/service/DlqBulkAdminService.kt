package com.example.billing.application.service

import com.example.billing.application.dto.DlqBulkJob
import com.example.billing.application.dto.DlqBulkResult
import com.example.billing.application.dto.DlqMessageFilter
import com.example.billing.application.exception.IllegalDlqOperationException
import com.example.billing.application.port.`in`.AuditLogger
import com.example.billing.application.port.`in`.DlqBulkAdminUseCase
import com.example.billing.application.port.out.DlqBulkJobRepository
import com.example.billing.application.port.out.DlqMessageStore
import com.example.billing.domain.audit.AuditAction
import com.example.billing.domain.audit.AuditActor
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.Executor

/**
 * DLQ 다건 (bulk) 운영. dry-run 기본 + `confirm=true` 시 비동기 worker 가 batch 단위로 처리.
 *
 * 각 항목은 별도 트랜잭션 ([TransactionTemplate.execute]) 으로 처리 — 한 건 실패가 다른 건을
 * 롤백하지 않음 → partial failure 추적 가능. 단건 처리는 [DlqMessageStore.replay] /
 * [DlqMessageStore.discard] 재사용 (audit 도 같은 결과 보장 — bulk 는 START / FINISH 1쌍만 발행
 * 하고 단건 audit 는 [DlqAdminService] 가 발행하던 패턴과 구분 — bulk 의 진행도/장애는 job
 * 결과로 추적).
 *
 * worker pool 은 `dlqBulkExecutor` (core 1 / max 2) — 동시 1건만 실행. vendor 부하 + outbox /
 * Kafka 폭주 방지. 결과는 [DlqBulkJobRepository] 에 보존 — 운영자가 polling.
 *
 * **billing 특유 — 돈 직결 안전망**: dry-run 강제. replay 의 경우 의도치 않은 재청구를 막기
 * 위해 운영자가 sample 을 눈으로 확인 후 `confirm=true` 로 재호출 해야 실 실행.
 */
@Service
open class DlqBulkAdminService(
    private val store: DlqMessageStore,
    private val auditLogger: AuditLogger,
    private val bulkJobRepository: DlqBulkJobRepository,
    @Qualifier("dlqBulkExecutor") private val bulkExecutor: Executor,
    txManager: PlatformTransactionManager,
) : DlqBulkAdminUseCase {

    private val txTemplate = TransactionTemplate(txManager)


    override fun bulkReplay(
        filter: DlqMessageFilter,
        confirm: Boolean,
        reason: String?,
        actor: AuditActor,
    ): DlqBulkResult = runBulk(DlqBulkJob.Operation.REPLAY, filter, confirm, reason, actor)

    override fun bulkDiscard(
        filter: DlqMessageFilter,
        confirm: Boolean,
        reason: String,
        actor: AuditActor,
    ): DlqBulkResult {
        require(reason.isNotBlank()) { "reason is required for bulk discard" }
        return runBulk(DlqBulkJob.Operation.DISCARD, filter, confirm, reason, actor)
    }

    override fun getBulkJob(jobId: UUID): Optional<DlqBulkJob> = bulkJobRepository.findById(jobId)

    // ============================================================
    // internal — confirm 여부에 따라 dry-run 응답 / 비동기 worker 실행 분기.
    // ============================================================

    private fun runBulk(
        operation: DlqBulkJob.Operation,
        filter: DlqMessageFilter,
        confirm: Boolean,
        reason: String?,
        actor: AuditActor,
    ): DlqBulkResult {
        if (filter.isUnknownConsumerGroup()) {
            // 결과 0건 short-circuit — dry-run/실행 가리지 않고 빈 응답.
            return DlqBulkResult.dryRun(0, emptyList())
        }

        val estimated = store.count(filter)
        val sampleIds = if (estimated == 0L) {
            emptyList()
        } else {
            store.search(filter, null, DlqMessageStore.SAMPLE_SIZE).map { it.messageId }
        }

        if (!confirm) {
            auditLogger.log(
                actor = actor,
                action = dryRunAction(operation),
                targetType = DlqAdminService.TARGET_TYPE,
                targetId = bulkTargetId(operation),
                beforeJson = null,
                afterJson = bulkAuditPayload(estimated, filter, reason, jobId = null),
                reason = reason,
            )
            return DlqBulkResult.dryRun(estimated, sampleIds)
        }

        val jobId = UUID.randomUUID()
        val now = Instant.now()
        val job = DlqBulkJob(
            jobId = jobId,
            operation = operation,
            state = DlqBulkJob.State.RUNNING,
            totalCount = estimated,
            processedCount = 0,
            successCount = 0,
            failureCount = 0,
            startedAt = now,
            finishedAt = null,
            firstError = null,
        )
        bulkJobRepository.create(job)

        auditLogger.log(
            actor = actor,
            action = startAction(operation),
            targetType = DlqAdminService.TARGET_TYPE,
            targetId = bulkTargetId(operation),
            beforeJson = null,
            afterJson = bulkAuditPayload(estimated, filter, reason, jobId = jobId),
            reason = reason,
        )

        // 비동기 실행 — MDC 만 worker 로 복사 (traceId 보존). actor 는 비동기 audit (FINISH) 에
        // 호출자 스냅샷 그대로 사용.
        val mdcSnapshot: Map<String, String>? = MDC.getCopyOfContextMap()
        val actorSnapshot = actor
        bulkExecutor.execute {
            if (mdcSnapshot != null) MDC.setContextMap(mdcSnapshot)
            try {
                executeBulk(jobId, operation, filter, reason, actorSnapshot)
            } finally {
                MDC.clear()
            }
        }

        return DlqBulkResult.executing(jobId, estimated, sampleIds)
    }

    /**
     * 비동기 worker 본체. cursor 페이지로 DLT 메시지를 batch 단위로 끌어와 각 항목을 별도
     * 트랜잭션으로 처리. 한 항목이 실패해도 다른 항목은 계속 진행 — partial failure 추적.
     *
     * cursor 진행 시 주의: replay / discard 가 성공하면 그 메시지는 다음 batch 의 결과에서
     * 빠질 수 있어 cursor 가 진행되지 않을 수 있음. 같은 batch 내에서 마지막 messageId 를
     * cursor 로 사용 — 어댑터가 cursor < messageId 조건으로 다음 batch 를 끌어옴.
     */
    private fun executeBulk(
        jobId: UUID,
        operation: DlqBulkJob.Operation,
        filter: DlqMessageFilter,
        reason: String?,
        actor: AuditActor,
    ) {
        var processed = 0L
        var success = 0L
        var failure = 0L
        var firstError: String? = null
        var cursor: String? = null

        while (true) {
            val batch = txTemplate.execute {
                store.search(filter, cursor, BULK_BATCH_SIZE)
            } ?: emptyList()
            if (batch.isEmpty()) break

            for (msg in batch) {
                processed++
                try {
                    txTemplate.execute {
                        when (operation) {
                            DlqBulkJob.Operation.REPLAY -> store.replay(msg.messageId)
                            DlqBulkJob.Operation.DISCARD ->
                                store.discard(msg.messageId, reason ?: "(bulk discard)")
                        }
                    }
                    success++
                } catch (e: IllegalDlqOperationException) {
                    // bulk 진행 중 다른 운영자가 같은 메시지를 먼저 처리한 경우 — skip.
                    log.debug(
                        "[dlq-bulk] {} skip messageId={} reason={}",
                        operation, msg.messageId, e.message,
                    )
                } catch (e: RuntimeException) {
                    failure++
                    if (firstError == null) firstError = "${e.javaClass.simpleName}: ${e.message}"
                    log.warn(
                        "[dlq-bulk] {} failed messageId={} reason={}",
                        operation, msg.messageId, e.message,
                    )
                }
            }

            // 진행도 publish — 운영자가 GET 으로 폴링.
            val startedAt = bulkJobRepository.findById(jobId)
                .map { it.startedAt }.orElse(Instant.now())
            val total = maxOf(
                processed,
                bulkJobRepository.findById(jobId).map { it.totalCount }.orElse(processed),
            )
            bulkJobRepository.update(
                DlqBulkJob(
                    jobId = jobId,
                    operation = operation,
                    state = DlqBulkJob.State.RUNNING,
                    totalCount = total,
                    processedCount = processed,
                    successCount = success,
                    failureCount = failure,
                    startedAt = startedAt,
                    finishedAt = null,
                    firstError = firstError,
                ),
            )

            cursor = batch.last().messageId
            if (batch.size < BULK_BATCH_SIZE) break
        }

        val finalState = when {
            failure == 0L -> DlqBulkJob.State.SUCCEEDED
            success == 0L -> DlqBulkJob.State.FAILED
            else -> DlqBulkJob.State.PARTIAL_FAILURE
        }
        val final = DlqBulkJob(
            jobId = jobId,
            operation = operation,
            state = finalState,
            totalCount = processed,
            processedCount = processed,
            successCount = success,
            failureCount = failure,
            startedAt = bulkJobRepository.findById(jobId)
                .map { it.startedAt }.orElse(Instant.now()),
            finishedAt = Instant.now(),
            firstError = firstError,
        )
        bulkJobRepository.update(final)

        auditLogger.log(
            actor = actor,
            action = finishAction(operation),
            targetType = DlqAdminService.TARGET_TYPE,
            targetId = bulkTargetId(operation),
            beforeJson = null,
            afterJson = AuditPayloads.`object`()
                .put("jobId", jobId.toString())
                .put("processed", processed)
                .put("success", success)
                .put("failure", failure)
                .put("state", finalState.name)
                .put("firstError", firstError)
                .build(),
            reason = reason,
        )
        log.info(
            "[dlq-bulk] {} finished jobId={} processed={} success={} failure={} state={}",
            operation, jobId, processed, success, failure, finalState,
        )
    }

    private fun bulkAuditPayload(
        estimated: Long,
        filter: DlqMessageFilter,
        reason: String?,
        jobId: UUID?,
    ): String = AuditPayloads.`object`()
        .put("jobId", jobId?.toString())
        .put("estimatedCount", estimated)
        .put("source", filter.resolvedSource()?.name ?: "(any)")
        .put("topic", filter.topic)
        .put("consumerGroup", filter.consumerGroup)
        .put("from", filter.from?.toString())
        .put("to", filter.to?.toString())
        .put("errorType", filter.errorType)
        .put("reason", reason)
        .build()

    private fun bulkTargetId(op: DlqBulkJob.Operation): String =
        "bulk-${op.name.lowercase()}"

    private fun dryRunAction(op: DlqBulkJob.Operation): AuditAction = when (op) {
        DlqBulkJob.Operation.REPLAY -> AuditAction.DLQ_BULK_REPLAY_DRYRUN
        DlqBulkJob.Operation.DISCARD -> AuditAction.DLQ_BULK_DISCARD_DRYRUN
    }

    private fun startAction(op: DlqBulkJob.Operation): AuditAction = when (op) {
        DlqBulkJob.Operation.REPLAY -> AuditAction.DLQ_BULK_REPLAY_START
        DlqBulkJob.Operation.DISCARD -> AuditAction.DLQ_BULK_DISCARD_START
    }

    private fun finishAction(op: DlqBulkJob.Operation): AuditAction = when (op) {
        DlqBulkJob.Operation.REPLAY -> AuditAction.DLQ_BULK_REPLAY_FINISH
        DlqBulkJob.Operation.DISCARD -> AuditAction.DLQ_BULK_DISCARD_FINISH
    }

    companion object {

        /** bulk worker 가 한 번에 끌어오는 batch size — 너무 크면 long tx, 작으면 polling 오버헤드. */
        const val BULK_BATCH_SIZE: Int = 100

        private val log = LoggerFactory.getLogger(DlqBulkAdminService::class.java)
    }
}
