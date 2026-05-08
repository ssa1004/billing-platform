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

    @Scheduled(fixedDelayString = "${billing.outbox.relay.poll-interval-ms:1000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishPending() {
        List<OutboxJpaEntity> batch = outboxRepository.findUnpublished(PageRequest.of(0, batchSize));
        if (batch.isEmpty()) return;

        int published = 0;
        for (OutboxJpaEntity msg : batch) {
            // 한 메시지 처리 중 *예상 못한* RuntimeException 이 발생해도 전체 batch 가 죽지
            // 않도록 격리. 다음 polling 에서 같은 메시지를 다시 시도 (at-least-once).
            try {
                if (publish(msg)) {
                    outboxRepository.markPublished(msg.getId(), clock.instant());
                    published++;
                }
            } catch (RuntimeException e) {
                log.warn("outbox relay unexpected error id={} type={} skipping",
                        msg.getId(), msg.getEventType(), e);
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
