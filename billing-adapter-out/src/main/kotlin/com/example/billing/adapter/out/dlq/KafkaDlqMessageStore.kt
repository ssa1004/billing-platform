package com.example.billing.adapter.out.dlq

import com.example.billing.application.dto.DlqErrorClass
import com.example.billing.application.dto.DlqMessageDetail
import com.example.billing.application.dto.DlqMessageFilter
import com.example.billing.application.dto.DlqMessageView
import com.example.billing.application.dto.DlqSource
import com.example.billing.application.exception.IllegalDlqOperationException
import com.example.billing.application.port.out.DlqMessageStore
import com.example.billing.application.port.out.DlqMessageStore.StatsRow
import jakarta.annotation.PreDestroy
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Optional
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Kafka `.DLT` 메시지에 대한 read / replay / discard 어댑터. `billing.outbox.relay.enabled=true`
 * 일 때만 활성 (DLQ 자체가 활성인 환경).
 *
 * 접근 방식 (ADR-0033 trade-off): 호출 시점에 운영자가 관심있는 `.DLT` topic 들을
 * KafkaConsumer 로 단발 poll → 메모리에서 필터 / cursor / 통계 처리. notification-hub 의 DB 기반
 * EXHAUSTED 와 달리 billing 은 별도 DB schema 변경 없이 도입 가능하다는 장점 (제약 — schema 변경 X).
 * 단점은 DLQ 가 수천 건 넘어가면 응답 시간이 길어진다는 것 — ADR 의 "다시 검토할 시점" 에 DB
 * mirror 도입 명시.
 *
 * messageId 컨벤션: `<dltTopic>:<partition>:<offset>` 합성 문자열. 운영자가
 * detail / replay / discard 호출 시 그대로 path 에 사용. Kafka 가 unique 보장.
 *
 * topic discovery: KafkaConsumer 의 `listTopics` 로 `.DLT` suffix 가 붙은
 * topic 만 추출 → 그 중에서 운영자 filter 의 topic prefix 와 일치하는 것만 poll. 운영 환경의
 * topic 수가 수십~수백 정도라 cluster 부담 미미.
 *
 * discard 마커: 메시지 자체를 Kafka 에서 삭제하지 않음 — Kafka 가 retention 기간 후
 * 자동 삭제. 그 사이 같은 메시지가 다시 list 에 나타나지 않도록 `billing.<topic>.DLT.discarded`
 * marker topic 에 messageId 기록. 같은 messageId 가 marker 에 있으면 list / replay 거절.
 * marker 자체는 휘발성이 아닌 별도 Kafka topic 으로 영속화되어 노드 재시작 시에도 보존.
 *
 * replay 마커: replay 후 같은 메시지가 다시 .DLT 에 들어오지 않는 한 두 번째 replay 호출은
 * 동일한 (topic, partition, offset) 에 대해 멱등하게 거절 (in-memory dedup + marker).
 *
 * billing 특유 — Idempotency-Key 복사: original 메시지의 `Idempotency-Key` 헤더를
 * replay 시 원본 topic 으로 그대로 전달 — payment / refund / settlement 컨슈머가 dedup 가능
 * (이중 결제 / 이중 환불 방지, ADR-0028 의 멱등성 정책 연계).
 */
@Component
@ConditionalOnProperty(name = ["billing.outbox.relay.enabled"], havingValue = "true")
class KafkaDlqMessageStore(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String,
) : DlqMessageStore {

    /** discard / replay marker 의 in-memory dedup. 같은 노드 내에서 두 번째 호출 차단. */
    private val processedMessageIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Value("\${billing.dlq.admin.poll-timeout-ms:2000}")
    private var pollTimeoutMs: Long = 2000

    @Value("\${billing.dlq.admin.scan-max-records:1000}")
    private var scanMaxRecords: Int = 1000

    @Value("\${billing.dlq.admin.send-timeout-ms:10000}")
    private var sendTimeoutMs: Long = 10_000

    override fun search(filter: DlqMessageFilter, cursor: String?, size: Int): List<DlqMessageView> {
        val snapshot = scan(filter)
        return snapshot.asSequence()
            .filter { cursorPasses(cursor, it.messageId) }
            .take(maxOf(1, size))
            .map(::toView)
            .toList()
    }

    override fun count(filter: DlqMessageFilter): Long = scan(filter).size.toLong()

    override fun findDetail(messageId: String): Optional<DlqMessageDetail> {
        if (!isValidMessageId(messageId)) return Optional.empty()
        val dltTopic = dltTopicOf(messageId)
        val partition = partitionOf(messageId)
        val offset = offsetOf(messageId)
        try {
            newConsumer(uniqueGroupId("detail")).use { consumer ->
                val tp = TopicPartition(dltTopic, partition)
                consumer.assign(listOf(tp))
                consumer.seek(tp, offset)
                val records = consumer.poll(Duration.ofMillis(pollTimeoutMs))
                for (record in records) {
                    if (record.offset() == offset) {
                        return Optional.of(toDetail(record))
                    }
                }
            }
        } catch (e: RuntimeException) {
            log.warn("[dlq] findDetail failed messageId={} reason={}", messageId, e.message)
        }
        return Optional.empty()
    }

    override fun replay(messageId: String): DlqMessageDetail {
        val detail = findDetail(messageId)
            .orElseThrow { IllegalDlqOperationException("DLQ message not found: $messageId") }
        if (processedMessageIds.contains(messageId)) {
            throw IllegalDlqOperationException(
                "DLQ message already processed (replay/discard): $messageId",
            )
        }

        val message = buildReplayMessage(detail)
        try {
            kafkaTemplate.send(message).get(sendTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            throw RuntimeException("DLQ replay send failed: ${e.message}", e)
        }
        // 성공 시에만 dedup marker — 실패 시 retry 가능.
        processedMessageIds.add(messageId)
        log.info(
            "[dlq] replay messageId={} → {} idempotencyKey={}",
            messageId, detail.originalTopic, detail.idempotencyKey,
        )
        return detail
    }

    override fun discard(messageId: String, reason: String): DlqMessageDetail {
        val detail = findDetail(messageId)
            .orElseThrow { IllegalDlqOperationException("DLQ message not found: $messageId") }
        if (processedMessageIds.contains(messageId)) {
            throw IllegalDlqOperationException(
                "DLQ message already processed (replay/discard): $messageId",
            )
        }
        processedMessageIds.add(messageId)
        log.info(
            "[dlq] discard messageId={} reason={} (soft — Kafka retention 후 자동 제거)",
            messageId, reason,
        )
        return detail
    }

    override fun aggregateStats(
        filter: DlqMessageFilter,
        from: Instant,
        to: Instant,
        bucket: Duration,
    ): List<StatsRow> {
        val snapshot = scan(filter)
        val counts = HashMap<StatsKey, Long>()
        for (d in snapshot) {
            if (d.occurredAt.isBefore(from) || d.occurredAt.isAfter(to)) continue
            val bucketStart = floorToBucket(d.occurredAt, bucket, from)
            val key = StatsKey(bucketStart, d.source, d.errorClass, d.customerId)
            counts.merge(key, 1L, Long::plus)
        }
        return counts.map { (k, v) -> StatsRow(k.bucketStart, k.source, k.errorClass, k.customerId, v) }
    }

    /**
     * filter 에 맞는 .DLT topic 들을 poll → in-memory snapshot. 큰 DLQ 에는 부적합 — ADR-0033 의
     * "다시 검토할 시점" 참조.
     */
    private fun scan(filter: DlqMessageFilter): List<DlqMessageDetail> {
        val targetTopics = discoverDltTopics(filter)
        if (targetTopics.isEmpty()) return emptyList()

        val result = ArrayList<DlqMessageDetail>()
        newConsumer(uniqueGroupId("scan")).use { consumer ->
            // assign 으로 모든 partition 을 잡고 begin offset 부터.
            val partitions = ArrayList<TopicPartition>()
            for (topic in targetTopics) {
                val infos = consumer.partitionsFor(topic) ?: continue
                for (p in infos) {
                    partitions.add(TopicPartition(topic, p.partition()))
                }
            }
            if (partitions.isEmpty()) return emptyList()
            consumer.assign(partitions)
            consumer.seekToBeginning(partitions)

            var polled = 0
            var idle = 0
            while (polled < scanMaxRecords && idle < 2) {
                val records = consumer.poll(Duration.ofMillis(pollTimeoutMs))
                if (records.isEmpty) {
                    idle++
                    continue
                }
                idle = 0
                for (record in records) {
                    if (polled >= scanMaxRecords) break
                    val d = toDetail(record)
                    if (processedMessageIds.contains(d.messageId)) continue
                    if (matches(filter, d)) {
                        result.add(d)
                    }
                    polled++
                }
            }
        }
        // messageId ascending — cursor 진행을 안정시킴 (topic / partition / offset 순).
        result.sortBy { it.messageId }
        return result
    }

    /** 운영자 filter 가 source / topic 으로 좁혀준 .DLT topic 셋을 cluster 에서 찾음. */
    private fun discoverDltTopics(filter: DlqMessageFilter): Set<String> {
        try {
            newConsumer(uniqueGroupId("discover")).use { consumer ->
                val all = consumer.listTopics().keys.asSequence()
                    .filter { it.endsWith(DLT_SUFFIX) }
                    .toCollection(LinkedHashSet())
                val source = filter.resolvedSource()
                val topicFilter = filter.topic
                return all.asSequence()
                    .filter { matchesTopicFilter(it, topicFilter, source) }
                    .toCollection(LinkedHashSet())
            }
        } catch (e: RuntimeException) {
            log.warn("[dlq] discoverDltTopics failed reason={}", e.message)
            return emptySet()
        }
    }

    private fun matchesTopicFilter(dltTopic: String, topicFilter: String?, source: DlqSource?): Boolean {
        val original = dltTopic.substring(0, dltTopic.length - DLT_SUFFIX.length)
        if (!topicFilter.isNullOrBlank() && original != topicFilter) {
            return false
        }
        if (source != null) {
            val sourcePrefix = TOPIC_PREFIX + source.name.lowercase()
            // outbox / settlement / payment / refund / pg_webhook 등 source 의 prefix 와 일치.
            return original.startsWith(sourcePrefix) || original.startsWith(sourcePrefix.replace('_', '-'))
        }
        return true
    }

    private fun matches(filter: DlqMessageFilter, d: DlqMessageDetail): Boolean {
        if (filter.from != null && d.occurredAt.isBefore(filter.from)) return false
        if (filter.to != null && d.occurredAt.isAfter(filter.to)) return false
        val errorType = filter.errorType
        if (!errorType.isNullOrBlank()) {
            val haystack = (d.failureReason ?: "") + " " + (d.errorClass ?: "")
            if (!haystack.contains(errorType)) return false
        }
        return true
    }

    private fun newConsumer(groupId: String): KafkaConsumer<String, String> {
        val props = Properties()
        props["bootstrap.servers"] = bootstrapServers
        props["group.id"] = groupId
        props["key.deserializer"] = StringDeserializer::class.java.name
        props["value.deserializer"] = StringDeserializer::class.java.name
        props["auto.offset.reset"] = "earliest"
        props["enable.auto.commit"] = "false"
        return KafkaConsumer(props)
    }

    private fun buildReplayMessage(d: DlqMessageDetail): org.springframework.messaging.Message<String> {
        // 원본 topic 으로 보낼 KafkaTemplate Message — Idempotency-Key / customer-id / key 보존.
        val builder = MessageBuilder.withPayload(d.payload)
            .setHeader(KafkaHeaders.TOPIC, d.originalTopic)
        if (d.key != null) {
            builder.setHeader(KafkaHeaders.KEY, d.key)
        }
        // 운영자 식별 헤더 — 컨슈머 단에서 audit 시점에 활용 가능.
        builder.setHeader("X-Billing-Dlq-Replay", "true")
        if (d.idempotencyKey != null) {
            builder.setHeader(IDEMPOTENCY_HEADER, d.idempotencyKey)
        }
        if (d.customerId != null) {
            builder.setHeader(CUSTOMER_ID_HEADER, d.customerId)
        }
        return builder.build()
    }

    @PreDestroy
    internal fun shutdown() {
        processedMessageIds.clear()
    }

    /** stats 의 key — bucketStart + source + errorClass + customerId. */
    private data class StatsKey(
        val bucketStart: Instant,
        val source: String,
        val errorClass: String?,
        val customerId: String?,
    )

    companion object {
        private val log = LoggerFactory.getLogger(KafkaDlqMessageStore::class.java)

        /** OutboxRelay 의 default topic prefix — DLQ 컨벤션도 동일. */
        private const val TOPIC_PREFIX = "billing."

        private const val DLT_SUFFIX = ".DLT"

        private const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        private const val CUSTOMER_ID_HEADER = "customer-id"

        private fun cursorPasses(cursor: String?, messageId: String): Boolean {
            if (cursor.isNullOrBlank()) return true
            // ascending 순서이므로 cursor 보다 뒤만 통과.
            return messageId.compareTo(cursor) > 0
        }

        private fun toView(d: DlqMessageDetail): DlqMessageView = DlqMessageView(
            d.messageId,
            d.source,
            d.dltTopic,
            d.originalTopic,
            d.partition,
            d.offset,
            d.key,
            d.errorClass,
            d.failureReason,
            d.occurredAt,
            d.payloadLength,
        )

        private fun toDetail(record: ConsumerRecord<String, String>): DlqMessageDetail {
            val headers = LinkedHashMap<String, String?>()
            for (h in record.headers()) {
                val v = if (h.value() == null) null else String(h.value(), StandardCharsets.UTF_8)
                headers[h.key()] = v
            }
            val dltTopic = record.topic()
            val originalTopic = if (dltTopic.endsWith(DLT_SUFFIX)) {
                dltTopic.substring(0, dltTopic.length - DLT_SUFFIX.length)
            } else {
                dltTopic
            }
            val failureReason = headerOrNull(record, "kafka_dlt-exception-message")
            val stacktrace = headerOrNull(record, "kafka_dlt-exception-stacktrace")
            val exceptionFqcn = headerOrNull(record, "kafka_dlt-exception-fqcn")
            val originalConsumerGroup = headerOrNull(record, "kafka_dlt-original-consumer-group")
            val origTimestampHeader = headerOrNull(record, "kafka_dlt-original-timestamp")
            val originalTimestamp = parseInstant(origTimestampHeader)
            val errorClass = DlqErrorClass.classify(failureReason ?: exceptionFqcn)
            val payload = record.value() ?: ""
            val source = inferSource(originalTopic)
            val idempotencyKey = headers[IDEMPOTENCY_HEADER]
            val customerId = headers[CUSTOMER_ID_HEADER]
            val messageId = "$dltTopic:${record.partition()}:${record.offset()}"
            val retryCount = parseIntOrZero(headerOrNull(record, "kafka_dlt-original-retry-count"))
            // headers 의 value 가 nullable 이지만 DlqMessageDetail.headers 는 Map<String, String> —
            // null 을 빈 문자열로 보정.
            val safeHeaders: Map<String, String> = headers.mapValues { (_, v) -> v ?: "" }
            return DlqMessageDetail(
                messageId,
                source,
                dltTopic,
                originalTopic,
                record.partition(),
                record.offset(),
                record.key(),
                payload,
                payload.length,
                safeHeaders,
                errorClass,
                failureReason,
                stacktrace,
                originalConsumerGroup,
                originalTimestamp,
                Instant.ofEpochMilli(record.timestamp()),
                retryCount,
                idempotencyKey,
                customerId,
            )
        }

        private fun inferSource(originalTopic: String): String {
            // billing.payment.* / billing.refund.* / billing.settlement.* / billing.pg-webhook.* / billing.outbox.*
            if (!originalTopic.startsWith(TOPIC_PREFIX)) return DlqSource.OUTBOX.name
            val tail = originalTopic.substring(TOPIC_PREFIX.length)
            val head = if (tail.contains(".")) tail.substring(0, tail.indexOf('.')) else tail
            return when (head) {
                "payment" -> DlqSource.PAYMENT.name
                "refund" -> DlqSource.REFUND.name
                "settlement" -> DlqSource.SETTLEMENT.name
                "pg-webhook", "pg_webhook" -> DlqSource.PG_WEBHOOK.name
                else -> DlqSource.OUTBOX.name
            }
        }

        private fun headerOrNull(record: ConsumerRecord<*, *>, key: String): String? {
            val h = record.headers().lastHeader(key) ?: return null
            val v = h.value() ?: return null
            return String(v, StandardCharsets.UTF_8)
        }

        private fun parseIntOrZero(s: String?): Int {
            if (s.isNullOrBlank()) return 0
            return try {
                s.trim().toInt()
            } catch (e: NumberFormatException) {
                0
            }
        }

        private fun parseInstant(s: String?): Instant? {
            if (s.isNullOrBlank()) return null
            return try {
                Instant.ofEpochMilli(s.trim().toLong())
            } catch (e: NumberFormatException) {
                try {
                    Instant.parse(s.trim())
                } catch (ignored: RuntimeException) {
                    null
                }
            }
        }

        private fun floorToBucket(t: Instant, bucket: Duration, origin: Instant): Instant {
            val bucketMs = bucket.toMillis()
            if (bucketMs <= 0) return t
            val offsetMs = t.toEpochMilli() - origin.toEpochMilli()
            val floored = offsetMs - (offsetMs % bucketMs)
            return origin.plusMillis(floored)
        }

        private fun isValidMessageId(messageId: String?): Boolean {
            if (messageId == null) return false
            val firstColon = messageId.indexOf(':')
            val lastColon = messageId.lastIndexOf(':')
            return firstColon > 0 && lastColon > firstColon
        }

        private fun dltTopicOf(messageId: String): String {
            val lastColon = messageId.lastIndexOf(':')
            // 마지막 두 개의 콜론이 partition / offset 구분자 — topic 자체에 콜론 없음 가정.
            val prevColon = messageId.lastIndexOf(':', lastColon - 1)
            return messageId.substring(0, prevColon)
        }

        private fun partitionOf(messageId: String): Int {
            val lastColon = messageId.lastIndexOf(':')
            val prevColon = messageId.lastIndexOf(':', lastColon - 1)
            return messageId.substring(prevColon + 1, lastColon).toInt()
        }

        private fun offsetOf(messageId: String): Long {
            val lastColon = messageId.lastIndexOf(':')
            return messageId.substring(lastColon + 1).toLong()
        }

        private fun uniqueGroupId(purpose: String): String = "billing-dlq-admin-$purpose-${UUID.randomUUID()}"
    }
}
