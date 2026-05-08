package com.example.billing.adapter.out.messaging;

import com.example.billing.adapter.out.persistence.outbox.OutboxJpaEntity;
import com.example.billing.adapter.out.persistence.outbox.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbox 테이블 → Kafka 로 메시지를 옮기는 relay. 동기로 send 하고 같은 트랜잭션 안에서만
 * markPublished 처리해서 안전성 확보. billing.outbox.relay.enabled=true 일 때만 활성.
 * 단일 인스턴스 전제 — 인스턴스가 여러 개라면 ShedLock 으로 한 번에 하나만 돌게 막아야 함.
 *
 * <p><b>트랜잭션 경계 — 메시지 단위</b>: 처음 배치 단위로 묶었더니 batch_size=100, 각
 * Kafka send 가 5초까지 블록 → 최악 500초 동안 DB 커넥션 1개 점유 → 풀 고갈 위험. 메시지
 * 단위로 분리해서 *읽기* (findUnpublished) 만 한 트랜잭션, *각 send + markPublished* 는 짧은
 * 별도 트랜잭션. 한 메시지가 오래 걸려도 다른 트랜잭션은 빠르게 닫힘.</p>
 *
 * <p>실패 시 markPublished 를 안 하므로 다음 polling 에서 재시도 (at-least-once, 최소 한 번
 * 전달이지만 중복 가능). 계속 실패하는 메시지 (poison pill) 는 별도 백로그로 처리.</p>
 */
@Component
@ConditionalOnProperty(name = "billing.outbox.relay.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;

    @Value("${billing.outbox.relay.batch-size:100}")
    private int batchSize;

    @Value("${billing.outbox.relay.send-timeout-ms:5000}")
    private long sendTimeoutMs;

    @Value("${billing.outbox.relay.topic-prefix:billing.}")
    private String topicPrefix;

    /**
     * 배치 시작 — 미발행 메시지 목록을 짧은 read-only 트랜잭션으로 가져온 뒤, 각 메시지는
     * {@link #publishOne}(메시지 단위 트랜잭션) 으로 따로 처리. 외부 호출(Kafka send) 동안 DB
     * 커넥션을 잡고 있지 않게 하기 위함.
     */
    @Scheduled(fixedDelayString = "${billing.outbox.relay.poll-interval-ms:1000}")
    public void publishPending() {
        List<OutboxJpaEntity> batch = fetchBatch();
        if (batch.isEmpty()) return;

        int published = 0;
        for (OutboxJpaEntity msg : batch) {
            // 한 메시지 처리 중 *예상 못한* RuntimeException 이 발생해도 전체 batch 가 죽지
            // 않도록 격리. 다음 polling 에서 같은 메시지를 다시 시도 (at-least-once).
            try {
                if (publishOne(msg)) {
                    published++;
                }
            } catch (RuntimeException e) {
                log.warn("outbox relay unexpected error id={} type={} skipping",
                        msg.getId(), msg.getEventType(), e);
            }
        }
        if (published > 0) log.debug("outbox relay published {}/{}", published, batch.size());
    }

    /**
     * 미발행 메시지 조회 — 짧은 read-only 트랜잭션. 외부 호출 없음.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<OutboxJpaEntity> fetchBatch() {
        return outboxRepository.findUnpublished(PageRequest.of(0, batchSize));
    }

    /**
     * 한 메시지 단위 트랜잭션 — Kafka send 성공 시 markPublished. send 가 실패하거나 timeout
     * 이면 markPublished 안 됨 → 다음 polling 에서 재시도.
     *
     * <p>주의: Kafka send 자체는 트랜잭션이 닫히기 전에 끝나야 markPublished 가 같은 트랜잭션
     * 안에 들어옴 (at-least-once 보장). send 가 길어지면 그만큼 트랜잭션이 길어지지만, 다른
     * 메시지의 트랜잭션과 분리되어 있어 풀 전체가 막히는 사고는 회피.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean publishOne(OutboxJpaEntity msg) {
        if (sendToKafka(msg)) {
            outboxRepository.markPublished(msg.getId(), clock.instant());
            return true;
        }
        return false;
    }

    private boolean sendToKafka(OutboxJpaEntity msg) {
        String topic = topicPrefix + msg.getAggregateType().toLowerCase()
                + "." + msg.getEventType().toLowerCase();
        try {
            kafkaTemplate.send(topic, msg.getAggregateId(), msg.getPayload())
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            log.warn("kafka publish timeout id={} topic={}", msg.getId(), topic);
        } catch (ExecutionException e) {
            log.warn("kafka publish failed id={} topic={} reason={}", msg.getId(), topic,
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("kafka publish interrupted id={}", msg.getId());
        }
        return false;
    }
}
