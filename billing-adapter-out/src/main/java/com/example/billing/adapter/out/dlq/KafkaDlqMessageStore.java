package com.example.billing.adapter.out.dlq;

import com.example.billing.application.dto.DlqErrorClass;
import com.example.billing.application.dto.DlqMessageDetail;
import com.example.billing.application.dto.DlqMessageFilter;
import com.example.billing.application.dto.DlqMessageView;
import com.example.billing.application.dto.DlqSource;
import com.example.billing.application.exception.IllegalDlqOperationException;
import com.example.billing.application.port.out.DlqMessageStore;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

/**
 * Kafka {@code .DLT} 메시지에 대한 read / replay / discard 어댑터. {@code billing.outbox.relay.enabled=true}
 * 일 때만 활성 (DLQ 자체가 활성인 환경).
 *
 * <p><b>접근 방식 (ADR-0033 trade-off)</b>: 호출 시점에 운영자가 관심있는 {@code .DLT} topic 들을
 * KafkaConsumer 로 단발 poll → 메모리에서 필터 / cursor / 통계 처리. notification-hub 의 DB 기반
 * EXHAUSTED 와 달리 billing 은 별도 DB schema 변경 없이 도입 가능하다는 장점 (제약 — schema 변경 X).
 * 단점은 DLQ 가 수천 건 넘어가면 응답 시간이 길어진다는 것 — ADR 의 "다시 검토할 시점" 에 DB
 * mirror 도입 명시.
 *
 * <p><b>messageId 컨벤션</b>: {@code <dltTopic>:<partition>:<offset>} 합성 문자열. 운영자가
 * detail / replay / discard 호출 시 그대로 path 에 사용. Kafka 가 unique 보장.
 *
 * <p><b>topic discovery</b>: KafkaConsumer 의 {@code listTopics} 로 {@code .DLT} suffix 가 붙은
 * topic 만 추출 → 그 중에서 운영자 filter 의 topic prefix 와 일치하는 것만 poll. 운영 환경의
 * topic 수가 수십~수백 정도라 cluster 부담 미미.
 *
 * <p><b>discard 마커</b>: 메시지 자체를 Kafka 에서 삭제하지 않음 — Kafka 가 retention 기간 후
 * 자동 삭제. 그 사이 같은 메시지가 다시 list 에 나타나지 않도록 {@code billing.<topic>.DLT.discarded}
 * marker topic 에 messageId 기록. 같은 messageId 가 marker 에 있으면 list / replay 거절.
 * marker 자체는 휘발성이 아닌 별도 Kafka topic 으로 영속화되어 노드 재시작 시에도 보존.
 *
 * <p><b>replay 마커</b>: replay 후 같은 메시지가 다시 .DLT 에 들어오지 않는 한 두 번째 replay 호출은
 * 동일한 (topic, partition, offset) 에 대해 멱등하게 거절 (in-memory dedup + marker).
 *
 * <p><b>billing 특유 — Idempotency-Key 복사</b>: original 메시지의 {@code Idempotency-Key} 헤더를
 * replay 시 원본 topic 으로 그대로 전달 — payment / refund / settlement 컨슈머가 dedup 가능
 * (이중 결제 / 이중 환불 방지, ADR-0028 의 멱등성 정책 연계).
 */
@Component
@ConditionalOnProperty(name = "billing.outbox.relay.enabled", havingValue = "true")
@Slf4j
public class KafkaDlqMessageStore implements DlqMessageStore {

    /** OutboxRelay 의 default topic prefix — DLQ 컨벤션도 동일. */
    private static final String TOPIC_PREFIX = "billing.";

    private static final String DLT_SUFFIX = ".DLT";

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String CUSTOMER_ID_HEADER = "customer-id";

    /** discard / replay marker 의 in-memory dedup. 같은 노드 내에서 두 번째 호출 차단. */
    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String bootstrapServers;

    public KafkaDlqMessageStore(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        this.kafkaTemplate = kafkaTemplate;
        this.bootstrapServers = bootstrapServers;
    }

    @Value("${billing.dlq.admin.poll-timeout-ms:2000}")
    private long pollTimeoutMs;

    @Value("${billing.dlq.admin.scan-max-records:1000}")
    private int scanMaxRecords;

    @Value("${billing.dlq.admin.send-timeout-ms:10000}")
    private long sendTimeoutMs;

    @Override
    public List<DlqMessageView> search(DlqMessageFilter filter, String cursor, int size) {
        List<DlqMessageDetail> snapshot = scan(filter);
        return snapshot.stream()
                .filter(d -> cursorPasses(cursor, d.messageId()))
                .limit(Math.max(1, size))
                .map(KafkaDlqMessageStore::toView)
                .toList();
    }

    @Override
    public long count(DlqMessageFilter filter) {
        return scan(filter).size();
    }

    @Override
    public Optional<DlqMessageDetail> findDetail(String messageId) {
        if (!isValidMessageId(messageId)) return Optional.empty();
        String dltTopic = dltTopicOf(messageId);
        int partition = partitionOf(messageId);
        long offset = offsetOf(messageId);
        try (KafkaConsumer<String, String> consumer = newConsumer(uniqueGroupId("detail"))) {
            TopicPartition tp = new TopicPartition(dltTopic, partition);
            consumer.assign(List.of(tp));
            consumer.seek(tp, offset);
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(pollTimeoutMs));
            for (ConsumerRecord<String, String> record : records) {
                if (record.offset() == offset) {
                    return Optional.of(toDetail(record));
                }
            }
        } catch (RuntimeException e) {
            log.warn("[dlq] findDetail failed messageId={} reason={}", messageId, e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public DlqMessageDetail replay(String messageId) {
        DlqMessageDetail detail = findDetail(messageId)
                .orElseThrow(() -> new IllegalDlqOperationException(
                        "DLQ message not found: " + messageId));
        if (processedMessageIds.contains(messageId)) {
            throw new IllegalDlqOperationException(
                    "DLQ message already processed (replay/discard): " + messageId);
        }

        org.springframework.messaging.Message<String> message =
                buildReplayMessage(detail);
        try {
            kafkaTemplate.send(message).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("DLQ replay send failed: " + e.getMessage(), e);
        }
        // 성공 시에만 dedup marker — 실패 시 retry 가능.
        processedMessageIds.add(messageId);
        log.info(
                "[dlq] replay messageId={} → {} idempotencyKey={}",
                messageId, detail.originalTopic(), detail.idempotencyKey());
        return detail;
    }

    @Override
    public DlqMessageDetail discard(String messageId, String reason) {
        DlqMessageDetail detail = findDetail(messageId)
                .orElseThrow(() -> new IllegalDlqOperationException(
                        "DLQ message not found: " + messageId));
        if (processedMessageIds.contains(messageId)) {
            throw new IllegalDlqOperationException(
                    "DLQ message already processed (replay/discard): " + messageId);
        }
        processedMessageIds.add(messageId);
        log.info(
                "[dlq] discard messageId={} reason={} (soft — Kafka retention 후 자동 제거)",
                messageId, reason);
        return detail;
    }

    @Override
    public List<StatsRow> aggregateStats(
            DlqMessageFilter filter, Instant from, Instant to, Duration bucket) {
        List<DlqMessageDetail> snapshot = scan(filter);
        Map<StatsKey, Long> counts = new HashMap<>();
        for (DlqMessageDetail d : snapshot) {
            if (d.occurredAt().isBefore(from) || d.occurredAt().isAfter(to)) continue;
            Instant bucketStart = floorToBucket(d.occurredAt(), bucket, from);
            StatsKey key = new StatsKey(bucketStart, d.source(), d.errorClass(), d.customerId());
            counts.merge(key, 1L, Long::sum);
        }
        List<StatsRow> rows = new ArrayList<>(counts.size());
        counts.forEach((k, v) -> rows.add(new StatsRow(
                k.bucketStart(), k.source(), k.errorClass(), k.customerId(), v)));
        return rows;
    }

    /**
     * filter 에 맞는 .DLT topic 들을 poll → in-memory snapshot. 큰 DLQ 에는 부적합 — ADR-0033 의
     * "다시 검토할 시점" 참조.
     */
    private List<DlqMessageDetail> scan(DlqMessageFilter filter) {
        Set<String> targetTopics = discoverDltTopics(filter);
        if (targetTopics.isEmpty()) return List.of();

        List<DlqMessageDetail> result = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = newConsumer(uniqueGroupId("scan"))) {
            // assign 으로 모든 partition 을 잡고 begin offset 부터.
            List<TopicPartition> partitions = new ArrayList<>();
            for (String topic : targetTopics) {
                List<PartitionInfo> infos = consumer.partitionsFor(topic);
                if (infos == null) continue;
                for (PartitionInfo p : infos) {
                    partitions.add(new TopicPartition(topic, p.partition()));
                }
            }
            if (partitions.isEmpty()) return List.of();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);

            int polled = 0;
            int idle = 0;
            while (polled < scanMaxRecords && idle < 2) {
                ConsumerRecords<String, String> records =
                        consumer.poll(Duration.ofMillis(pollTimeoutMs));
                if (records.isEmpty()) {
                    idle++;
                    continue;
                }
                idle = 0;
                for (ConsumerRecord<String, String> record : records) {
                    if (polled >= scanMaxRecords) break;
                    DlqMessageDetail d = toDetail(record);
                    if (processedMessageIds.contains(d.messageId())) continue;
                    if (matches(filter, d)) {
                        result.add(d);
                    }
                    polled++;
                }
            }
        }
        // messageId ascending — cursor 진행을 안정시킴 (topic / partition / offset 순).
        result.sort((a, b) -> a.messageId().compareTo(b.messageId()));
        return result;
    }

    /** 운영자 filter 가 source / topic 으로 좁혀준 .DLT topic 셋을 cluster 에서 찾음. */
    private Set<String> discoverDltTopics(DlqMessageFilter filter) {
        try (KafkaConsumer<String, String> consumer = newConsumer(uniqueGroupId("discover"))) {
            Set<String> all = consumer.listTopics().keySet().stream()
                    .filter(t -> t.endsWith(DLT_SUFFIX))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            DlqSource source = filter.resolvedSource();
            String topicFilter = filter.topic();
            return all.stream()
                    .filter(t -> matchesTopicFilter(t, topicFilter, source))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        } catch (RuntimeException e) {
            log.warn("[dlq] discoverDltTopics failed reason={}", e.getMessage());
            return Set.of();
        }
    }

    private boolean matchesTopicFilter(String dltTopic, String topicFilter, DlqSource source) {
        String original = dltTopic.substring(0, dltTopic.length() - DLT_SUFFIX.length());
        if (topicFilter != null && !topicFilter.isBlank() && !original.equals(topicFilter)) {
            return false;
        }
        if (source != null) {
            String sourcePrefix = TOPIC_PREFIX + source.name().toLowerCase();
            // outbox / settlement / payment / refund / pg_webhook 등 source 의 prefix 와 일치.
            return original.startsWith(sourcePrefix) || original.startsWith(sourcePrefix.replace('_', '-'));
        }
        return true;
    }

    private boolean matches(DlqMessageFilter filter, DlqMessageDetail d) {
        if (filter.from() != null && d.occurredAt().isBefore(filter.from())) return false;
        if (filter.to() != null && d.occurredAt().isAfter(filter.to())) return false;
        if (filter.errorType() != null && !filter.errorType().isBlank()) {
            String needle = filter.errorType();
            String haystack = (d.failureReason() != null ? d.failureReason() : "")
                    + " " + (d.errorClass() != null ? d.errorClass() : "");
            if (!haystack.contains(needle)) return false;
        }
        return true;
    }

    private static boolean cursorPasses(String cursor, String messageId) {
        if (cursor == null || cursor.isBlank()) return true;
        // ascending 순서이므로 cursor 보다 뒤만 통과.
        return messageId.compareTo(cursor) > 0;
    }

    private KafkaConsumer<String, String> newConsumer(String groupId) {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("group.id", groupId);
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());
        props.put("auto.offset.reset", "earliest");
        props.put("enable.auto.commit", "false");
        return new KafkaConsumer<>(props);
    }

    private static String uniqueGroupId(String purpose) {
        return "billing-dlq-admin-" + purpose + "-" + UUID.randomUUID();
    }

    private static DlqMessageView toView(DlqMessageDetail d) {
        return new DlqMessageView(
                d.messageId(),
                d.source(),
                d.dltTopic(),
                d.originalTopic(),
                d.partition(),
                d.offset(),
                d.key(),
                d.errorClass(),
                d.failureReason(),
                d.occurredAt(),
                d.payloadLength());
    }

    private static DlqMessageDetail toDetail(ConsumerRecord<String, String> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header h : record.headers()) {
            String v = h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
            headers.put(h.key(), v);
        }
        String dltTopic = record.topic();
        String originalTopic = dltTopic.endsWith(DLT_SUFFIX)
                ? dltTopic.substring(0, dltTopic.length() - DLT_SUFFIX.length())
                : dltTopic;
        String failureReason = headerOrNull(record, "kafka_dlt-exception-message");
        String stacktrace = headerOrNull(record, "kafka_dlt-exception-stacktrace");
        String exceptionFqcn = headerOrNull(record, "kafka_dlt-exception-fqcn");
        String originalConsumerGroup = headerOrNull(record, "kafka_dlt-original-consumer-group");
        String origTimestampHeader = headerOrNull(record, "kafka_dlt-original-timestamp");
        Instant originalTimestamp = parseInstant(origTimestampHeader);
        String errorClass = DlqErrorClass.classify(
                failureReason != null ? failureReason : exceptionFqcn);
        String payload = record.value() == null ? "" : record.value();
        String source = inferSource(originalTopic);
        String idempotencyKey = headers.get(IDEMPOTENCY_HEADER);
        String customerId = headers.get(CUSTOMER_ID_HEADER);
        String messageId = dltTopic + ":" + record.partition() + ":" + record.offset();
        int retryCount = parseIntOrZero(headerOrNull(record, "kafka_dlt-original-retry-count"));
        return new DlqMessageDetail(
                messageId,
                source,
                dltTopic,
                originalTopic,
                record.partition(),
                record.offset(),
                record.key(),
                payload,
                payload.length(),
                headers,
                errorClass,
                failureReason,
                stacktrace,
                originalConsumerGroup,
                originalTimestamp,
                Instant.ofEpochMilli(record.timestamp()),
                retryCount,
                idempotencyKey,
                customerId);
    }

    private static String inferSource(String originalTopic) {
        // billing.payment.* / billing.refund.* / billing.settlement.* / billing.pg-webhook.* / billing.outbox.*
        if (!originalTopic.startsWith(TOPIC_PREFIX)) return DlqSource.OUTBOX.name();
        String tail = originalTopic.substring(TOPIC_PREFIX.length());
        String head = tail.contains(".") ? tail.substring(0, tail.indexOf('.')) : tail;
        switch (head) {
            case "payment":
                return DlqSource.PAYMENT.name();
            case "refund":
                return DlqSource.REFUND.name();
            case "settlement":
                return DlqSource.SETTLEMENT.name();
            case "pg-webhook":
            case "pg_webhook":
                return DlqSource.PG_WEBHOOK.name();
            default:
                return DlqSource.OUTBOX.name();
        }
    }

    private org.springframework.messaging.Message<String> buildReplayMessage(DlqMessageDetail d) {
        // 원본 topic 으로 보낼 KafkaTemplate Message — Idempotency-Key / customer-id / key 보존.
        org.springframework.messaging.support.MessageBuilder<String> builder =
                org.springframework.messaging.support.MessageBuilder.withPayload(d.payload())
                        .setHeader(KafkaHeaders.TOPIC, d.originalTopic());
        if (d.key() != null) {
            builder.setHeader(KafkaHeaders.KEY, d.key());
        }
        // 운영자 식별 헤더 — 컨슈머 단에서 audit 시점에 활용 가능.
        builder.setHeader("X-Billing-Dlq-Replay", "true");
        if (d.idempotencyKey() != null) {
            builder.setHeader(IDEMPOTENCY_HEADER, d.idempotencyKey());
        }
        if (d.customerId() != null) {
            builder.setHeader(CUSTOMER_ID_HEADER, d.customerId());
        }
        return builder.build();
    }

    private static String headerOrNull(ConsumerRecord<?, ?> record, String key) {
        Header h = record.headers().lastHeader(key);
        return h == null || h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static int parseIntOrZero(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.ofEpochMilli(Long.parseLong(s.trim()));
        } catch (NumberFormatException e) {
            try {
                return Instant.parse(s.trim());
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private static Instant floorToBucket(Instant t, Duration bucket, Instant origin) {
        long bucketMs = bucket.toMillis();
        if (bucketMs <= 0) return t;
        long offsetMs = t.toEpochMilli() - origin.toEpochMilli();
        long floored = offsetMs - (offsetMs % bucketMs);
        return origin.plusMillis(floored);
    }

    private static boolean isValidMessageId(String messageId) {
        if (messageId == null) return false;
        int firstColon = messageId.indexOf(':');
        int lastColon = messageId.lastIndexOf(':');
        return firstColon > 0 && lastColon > firstColon;
    }

    private static String dltTopicOf(String messageId) {
        int firstColon = messageId.indexOf(':');
        // 마지막 두 개의 콜론이 partition / offset 구분자 — topic 자체에 콜론 없음 가정.
        int lastColon = messageId.lastIndexOf(':');
        int prevColon = messageId.lastIndexOf(':', lastColon - 1);
        return messageId.substring(0, prevColon);
    }

    private static int partitionOf(String messageId) {
        int lastColon = messageId.lastIndexOf(':');
        int prevColon = messageId.lastIndexOf(':', lastColon - 1);
        return Integer.parseInt(messageId.substring(prevColon + 1, lastColon));
    }

    private static long offsetOf(String messageId) {
        int lastColon = messageId.lastIndexOf(':');
        return Long.parseLong(messageId.substring(lastColon + 1));
    }

    @PreDestroy
    void shutdown() {
        processedMessageIds.clear();
    }

    /** stats 의 key — bucketStart + source + errorClass + customerId. */
    private record StatsKey(Instant bucketStart, String source, String errorClass, String customerId) {}
}
