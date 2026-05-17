package com.example.billing.application.dto

import java.time.Instant
import java.util.UUID

/**
 * bulk-replay / bulk-discard 의 진행 / 결과. dry-run 이후 사용자가 `confirm=true` 로 실행하면
 * 비동기 job 이 시작되고, 호출자는 [DlqBulkResult.jobId] 를 받아 `GET /bulk-jobs/{id}` 로 폴링.
 *
 * 이 모듈에서는 in-memory 로 트래킹 (ADR-0033 — 실서비스는 DB / Redis 권장). 노드 재시작 시
 * 진행 중인 job 정보는 손실 — 안전성 측면에서 명시.
 */
@JvmRecord
data class DlqBulkJob(
    val jobId: UUID,
    val operation: Operation,
    val state: State,
    val totalCount: Long,
    val processedCount: Long,
    val successCount: Long,
    val failureCount: Long,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val firstError: String?,
) {

    enum class Operation { REPLAY, DISCARD }

    enum class State {
        RUNNING,
        SUCCEEDED,
        PARTIAL_FAILURE,
        FAILED,
    }
}
