package com.example.billing.application.dto

import java.time.Instant
import java.util.UUID

/**
 * bulk-replay / bulk-discard 의 응답.
 *
 * 두 모드:
 * - **dry-run** (default 또는 `confirm=false`) — Kafka / DB 에 손 안 댐. [estimatedCount] 와
 *   [sampleMessageIds] (최대 10개) 만 채워 미리보기 제공. [jobId] = null.
 * - **execute** (`confirm=true`) — 비동기 job 시작. [jobId] 로 [DlqBulkJob] 진행 추적.
 *   [estimatedCount] = 시작 시점의 대상 개수 추정 (실행 중 다른 메시지가 새로 .DLT 로 들어와도
 *   이번 job 은 처음에 잡은 (topic, partition, offset) set 만 처리).
 *
 * **billing 특유 — 돈 직결 안전망**: dry-run 모드에선 응답 본문에 `requireConfirmation` 플래그
 * 같은 명시 필드 없이도 [mode] = DRY_RUN 로 강제 표시. 운영자는 sample 을 확인 후 `confirm=true`
 * 로 다시 호출해야 실제 발행이 일어남. 한 번에 수천 건의 재청구 / 재정산 사고 방지.
 */
@JvmRecord
data class DlqBulkResult(
    val mode: Mode,
    val estimatedCount: Long,
    val sampleMessageIds: List<String>,
    val jobId: UUID?,
    val startedAt: Instant?,
) {

    enum class Mode {
        DRY_RUN,
        EXECUTING,
    }

    companion object {

        @JvmStatic
        fun dryRun(estimated: Long, samples: List<String>): DlqBulkResult =
            DlqBulkResult(Mode.DRY_RUN, estimated, samples, null, null)

        @JvmStatic
        fun executing(jobId: UUID, estimated: Long, samples: List<String>): DlqBulkResult =
            DlqBulkResult(Mode.EXECUTING, estimated, samples, jobId, Instant.now())
    }
}
