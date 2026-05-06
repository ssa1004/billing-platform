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
 * Outbox → Kafka relay. 동기 send + tx 안에서 markPublished (트랜잭션 안전).
 * billing.outbox.relay.enabled=true 일 때만 활성. 단일 인스턴스 전제 — 멀티 시 ShedLock.
 *
 * <p>실패 시 markPublished 안 함 → 다음 polling 에서 재시도 (at-least-once).
 * Poison pill (영구 실패) 은 별도 백로그.</p>
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

    @Scheduled(fixedDelayString = "${billing.outbox.relay.poll-interval-ms:1000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishPending() {
        List<OutboxJpaEntity> batch = outboxRepository.findUnpublished(PageRequest.of(0, batchSize));
        if (batch.isEmpty()) return;

        int published = 0;
        for (OutboxJpaEntity msg : batch) {
            if (publish(msg)) {
                outboxRepository.markPublished(msg.getId(), clock.instant());
                published++;
            }
        }
        if (published > 0) log.debug("outbox relay published {}/{}", published, batch.size());
    }

    private boolean publish(OutboxJpaEntity msg) {
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
