package com.example.billing.application.dto

import java.time.Instant

/**
 * DLQ list / stats / bulk 의 공통 필터. 모든 필드 optional.
 *
 * billing 의 DLQ 는 Kafka `.DLT` (Dead Letter Topic) 단위라 notification-hub 처럼 channel enum 이
 * 아닌 [source] 카테고리 (`payment` / `refund` / `settlement` / `pg-webhook` / `outbox`) 로 분리.
 * source 와 topic 모두 주면 source 우선.
 *
 * - [source] — billing 도메인 원천 카테고리. ADR-0033 의 `DlqSource` 와 같은 enum 셋.
 * - [topic] — `billing.<source>.<event>` 형식 (OutboxRelay topic 컨벤션). DLT suffix 는 자동 부착.
 * - [consumerGroup] — Kafka consumer group id. 현재 시스템은 source 별로 1개 group 만 사용 —
 *   다른 값을 주면 결과 0건 (호환성 자리).
 * - [from] / [to] — Kafka record 의 timestamp 범위.
 * - [errorType] — DLT 헤더 `kafka_dlt-exception-fqcn` 또는 `failure_reason` 의 부분 일치.
 */
@JvmRecord
data class DlqMessageFilter(
    val source: DlqSource?,
    val topic: String?,
    val consumerGroup: String?,
    val from: Instant?,
    val to: Instant?,
    val errorType: String?,
) {

    /** [topic] 이 채워졌고 [source] 가 비었으면 topic 에서 source 를 유도. */
    fun resolvedSource(): DlqSource? {
        if (source != null) return source
        if (topic.isNullOrBlank()) return null
        val prefix = TOPIC_PREFIX
        if (!topic.startsWith(prefix)) return null
        val suffix = topic.removePrefix(prefix).substringBefore('.').uppercase()
        return DlqSource.entries.firstOrNull { it.name == suffix }
    }

    /**
     * [consumerGroup] 이 명시되었으나 현재 시스템이 지원하지 않는 그룹인지. 결과 0건 보장용
     * short-circuit. 현재 알고 있는 group prefix 는 `billing-`.
     */
    fun isUnknownConsumerGroup(): Boolean =
        !consumerGroup.isNullOrBlank() && !consumerGroup.startsWith("billing-")

    companion object {

        /** OutboxRelay topic prefix (`billing.outbox.relay.topic-prefix` 의 default). */
        const val TOPIC_PREFIX: String = "billing."

        @JvmField
        val EMPTY: DlqMessageFilter = DlqMessageFilter(null, null, null, null, null, null)
    }
}

/**
 * DLQ source — billing 도메인 카테고리. **돈에 직결되는 source 우선 정렬**:
 * [PAYMENT] / [REFUND] / [SETTLEMENT] 가 dry-run / audit 1순위.
 *
 * 새 source 추가 시 다운스트림 metric / 대시보드도 같이 검토.
 */
enum class DlqSource {
    /** payment authorization / capture 실패 후 outbox → Kafka 까지 도달해 컨슈머가 N회 실패한 경우. */
    PAYMENT,

    /** refund 발행 / PG 호출 실패 컨슈머. */
    REFUND,

    /** settlement run 의 per-row 결과 컨슈머. */
    SETTLEMENT,

    /** PG webhook 수신 후 처리 컨슈머 (idempotency 미스, body fingerprint mismatch 등). */
    PG_WEBHOOK,

    /** OutboxRelay 가 발행하는 일반 도메인 이벤트의 컨슈머 (invoice / wallet 등). */
    OUTBOX,
}
