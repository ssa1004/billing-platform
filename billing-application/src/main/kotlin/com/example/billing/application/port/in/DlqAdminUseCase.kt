package com.example.billing.application.port.`in`

import com.example.billing.application.dto.DlqListPage
import com.example.billing.application.dto.DlqMessageDetail
import com.example.billing.application.dto.DlqMessageFilter
import com.example.billing.application.dto.DlqMessageView
import com.example.billing.application.dto.DlqStats
import com.example.billing.domain.audit.AuditActor
import java.time.Duration
import java.time.Instant
import java.util.Optional

/**
 * DLQ (`.DLT` 메시지) 단건 운영. 운영자 (ADMIN role) 만 호출. notification-hub ADR-0015 의
 * `DlqAdminUseCase` 와 동등한 형태 — billing 컨텍스트에 맞춰 enum 만 교체 (ChannelType →
 * DlqSource).
 *
 * 다건 (bulk) 은 [DlqBulkAdminUseCase] 로 분리 — 단건 호출자가 무거운 의존성 (executor / job
 * repository) 을 알 필요가 없도록.
 */
interface DlqAdminUseCase {

    /**
     * filter 조건으로 cursor 페이지네이션. [size] 1~200 사이로 캡. 결과의 [DlqListPage.nextCursor]
     * 가 null 이면 마지막 페이지.
     */
    fun search(filter: DlqMessageFilter, cursor: String?, size: Int): DlqListPage

    /**
     * 단건 상세 — full payload + headers + retry context + 원본 stacktrace. 없으면 [Optional.empty].
     */
    fun detail(messageId: String): Optional<DlqMessageDetail>

    /**
     * 단건 replay — 원본 topic 으로 재발행. 두 번째 호출 시
     * [com.example.billing.application.exception.IllegalDlqOperationException] (controller →
     * 409 `ILLEGAL_DLQ_OPERATION`).
     *
     * billing 특유: Idempotency-Key 헤더가 복사되어 컨슈머가 dedup 가능 (이중 결제 방지).
     */
    fun replay(messageId: String, actor: AuditActor): DlqMessageView

    /**
     * 단건 discard — 영구 종료 (soft, hard DELETE 차단). [reason] 필수. audit 에 actor /
     * targetId (`<dltTopic>:<partition>:<offset>`) / 결과 / customerId 기록.
     */
    fun discard(messageId: String, reason: String, actor: AuditActor): DlqMessageView

    /**
     * 시간 [from]~[to] 범위의 DLT 메시지를 [bucket] 단위로 집계. [from] / [to] null 이면 각각
     * "최근 24h 시작" / "now" 로 대체. bucket null 이면 1시간.
     */
    fun stats(filter: DlqMessageFilter, from: Instant?, to: Instant?, bucket: Duration?): DlqStats
}
