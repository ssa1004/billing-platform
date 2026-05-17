package com.example.billing.application.port.`in`

import com.example.billing.application.dto.DlqBulkJob
import com.example.billing.application.dto.DlqBulkResult
import com.example.billing.application.dto.DlqMessageFilter
import com.example.billing.domain.audit.AuditActor
import java.util.Optional
import java.util.UUID

/**
 * DLQ 다건 작업 — bulk-replay / bulk-discard + 비동기 job 진행도 조회.
 *
 * dry-run 강제 + idempotency / partial failure / audit 정책은 ADR-0033 참조. 분리 이유:
 * - 단건 endpoint (호환 보존) 와 다건 endpoint (위험 작업 + 비동기) 의 책임 분리.
 * - 단건만 필요한 호출자는 [DlqAdminUseCase] 만 의존하면 됨.
 *
 * **billing 특유 — 돈 직결**: bulk-replay 가 의도치 않은 재청구 위험을 안고 있어 [confirm] =
 * false (default) 면 강제 dry-run. 운영자가 sample 확인 후 `confirm=true` 로 재호출해야 실
 * 실행. discard 는 [reason] 필수 (NotBlank).
 */
interface DlqBulkAdminUseCase {

    /**
     * bulk-replay. [confirm] = false (default) 면 dry-run — 대상 개수 + sample messageId 만 반환.
     * true 면 비동기 job 시작 후 jobId 반환. job 결과는 [getBulkJob] 으로 조회.
     */
    fun bulkReplay(
        filter: DlqMessageFilter,
        confirm: Boolean,
        reason: String?,
        actor: AuditActor,
    ): DlqBulkResult

    /** bulk-discard. dry-run 의미는 [bulkReplay] 와 동일. [reason] 필수. */
    fun bulkDiscard(
        filter: DlqMessageFilter,
        confirm: Boolean,
        reason: String,
        actor: AuditActor,
    ): DlqBulkResult

    /** 비동기 bulk job 상태 조회. */
    fun getBulkJob(jobId: UUID): Optional<DlqBulkJob>
}
