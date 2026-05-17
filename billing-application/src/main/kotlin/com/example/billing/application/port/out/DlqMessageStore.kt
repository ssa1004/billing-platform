package com.example.billing.application.port.out

import com.example.billing.application.dto.DlqMessageDetail
import com.example.billing.application.dto.DlqMessageFilter
import com.example.billing.application.dto.DlqMessageView
import com.example.billing.application.dto.DlqStats
import java.time.Duration
import java.time.Instant
import java.util.Optional

/**
 * Kafka `.DLT` topic 에 쌓인 메시지에 대한 read / replay / discard 추상화.
 *
 * Kafka 메시지는 별도 PK 가 없고 (topic, partition, offset) 가 자연 키 — 이 port 의 모든
 * `messageId` 는 `<dltTopic>:<partition>:<offset>` 합성 문자열. 어댑터 (`KafkaDlqMessageStore`)
 * 가 그 문자열을 해석 / 발급한다.
 *
 * **왜 port 로 빼는가**:
 * - use case (DlqAdminService / DlqBulkAdminService) 가 Kafka client 에 직접 의존하지 않게 분리.
 * - 추후 외부 DLQ 저장소 (DB / S3 archival) 추가 시 어댑터만 교체.
 * - 테스트에서 in-memory 어댑터로 service 로직만 빠르게 검증.
 *
 * **replay / discard 의 의미** (billing 컨텍스트, ADR-0033):
 * - replay — DLT 메시지를 원본 topic 으로 재발행. 컨슈머가 다시 처리. 멱등성은 컨슈머 책임
 *   (payment / refund 는 Idempotency-Key 기반 dedup — ADR-0006 / ADR-0028).
 * - discard — DLT 메시지를 영구 종료. 현 어댑터는 logical 마커 (audit 후 reprocess 안 함)
 *   로 처리, 물리 삭제 안 함 (hard DELETE 차단).
 */
interface DlqMessageStore {

    /** filter 조건으로 cursor 페이지네이션. [cursor] 가 null 이면 처음부터. */
    fun search(filter: DlqMessageFilter, cursor: String?, size: Int): List<DlqMessageView>

    /** [search] 와 같은 필터 조건의 전체 개수. dry-run estimate / stats 합계용. */
    fun count(filter: DlqMessageFilter): Long

    /** 단건 상세 — 없으면 [Optional.empty]. 호출자는 controller 단에서 404 로 매핑. */
    fun findDetail(messageId: String): Optional<DlqMessageDetail>

    /**
     * 한 메시지 replay — 원본 topic 으로 재발행 후 어댑터 내부적으로 commit / 마커 박음.
     * 이미 처리된 메시지면
     * [com.example.billing.application.exception.IllegalDlqOperationException] (controller 단
     * 에서 409 ILLEGAL_DLQ_OPERATION 로 매핑).
     *
     * **billing 특유**: replay 발행 시 원본 메시지의 `Idempotency-Key` 헤더가 그대로 복사된다 —
     * 컨슈머가 같은 키로 두 번째 도착을 dedup 가능 (이중 결제 / 이중 환불 방지).
     */
    fun replay(messageId: String): DlqMessageDetail

    /**
     * 한 메시지 discard — 영구 종료 (soft, 마커 박음). [reason] 은 audit 에 기록. 이미 처리된
     * 메시지면 [com.example.billing.application.exception.IllegalDlqOperationException].
     */
    fun discard(messageId: String, reason: String): DlqMessageDetail

    /**
     * 통계 — 시간 [from]~[to] 범위의 DLT 메시지에 대해 (bucket, source, errorClass, customerId)
     * × count. 어댑터는 raw row 만 반환, use case 가 차원별 합계 계산. notification-hub ADR-0015
     * 의 `aggregateExhaustedStats` 와 같은 분리 원칙.
     */
    fun aggregateStats(filter: DlqMessageFilter, from: Instant, to: Instant, bucket: Duration): List<StatsRow>

    @JvmRecord
    data class StatsRow(
        val bucketStart: Instant,
        val source: String,
        val errorClass: String?,
        val customerId: String?,
        val count: Long,
    )

    companion object {

        /** 운영 화면에서 한 페이지에 보일 수 있는 messageId sample 상한 (dry-run). */
        const val SAMPLE_SIZE: Int = 10
    }
}
