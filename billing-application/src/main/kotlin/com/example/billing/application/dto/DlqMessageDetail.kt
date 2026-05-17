package com.example.billing.application.dto

import java.time.Instant

/**
 * DLQ 단건 상세 — payload 전체 + 전체 headers + retry context (DLT 헤더 기반).
 *
 * Spring Kafka 의 `DeadLetterPublishingRecoverer` 가 [`KafkaHeaders.DLT_*` 표준 헤더](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html#dead-letter-headers)
 * 를 자동 부착 — 이 detail 은 그 헤더를 의미별로 풀어 보여준다.
 *
 * `originalStacktrace` 는 운영자가 forensic 단계에서 어떤 컨슈머가 어떤 라인에서 던졌는지
 * 파악하는 1순위 자료. payload + stacktrace 합쳐 사고 재현 가능 (테스트로 옮길 수 있음).
 *
 * `idempotencyKey` 는 billing 특유 — payment / refund / settlement 컨슈머가 멱등 처리
 * (Idempotency-Key 기반 dedup, ADR-0028) 하기 위해 사용. replay 시 같은 키로 다시 도착해도
 * 안전함을 운영자가 확인할 수 있도록 노출.
 */
@JvmRecord
data class DlqMessageDetail(
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
    /** billing 특유 — Idempotency-Key 헤더 (있을 때만). replay 멱등성 확인용. */
    val idempotencyKey: String?,
    /** billing 특유 — `customer-id` 헤더 (있을 때만). audit 의 targetId 로도 사용. */
    val customerId: String?,
)

/**
 * `failureReason` 또는 `kafka_dlt-exception-fqcn` 헤더에서 첫 token 을 잘라 error class 로.
 * - `com.example.billing.application.exception.RefundFailedException: PG 5xx` → `RefundFailedException`
 * - `PG 5xx` → `PG 5xx`
 * - null / blank → null
 *
 * notification-hub 의 `DlqErrorClass` 와 동일 로직 — 별도 enum 강제 없이 새 예외 타입이 자연스럽게 분류.
 */
object DlqErrorClass {

    @JvmStatic
    fun classify(failureReason: String?): String? {
        if (failureReason.isNullOrBlank()) return null
        val firstColon = failureReason.indexOf(':')
        val firstSpace = failureReason.indexOf(' ')
        val cut = when {
            firstColon > 0 && (firstSpace < 0 || firstColon < firstSpace) -> firstColon
            firstSpace > 0 -> firstSpace
            else -> failureReason.length
        }
        val firstToken = failureReason.substring(0, cut).trim()
        if (firstToken.isEmpty()) return null
        // FQCN 이면 simple name 으로 — `c.e.b.X.Y.RefundFailedException` → `RefundFailedException`.
        val lastDot = firstToken.lastIndexOf('.')
        return if (lastDot in 0 until firstToken.lastIndex) firstToken.substring(lastDot + 1) else firstToken
    }
}
