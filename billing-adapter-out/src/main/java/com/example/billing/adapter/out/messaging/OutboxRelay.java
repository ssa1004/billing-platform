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
 *
 * <p><b>수평 확장</b>: {@link OutboxRepository#findUnpublished} 가
 * {@code PESSIMISTIC_WRITE + lock.timeout=0} → Postgres {@code FOR UPDATE SKIP LOCKED} 로
 * 변환되므로 여러 인스턴스가 동시에 polling 해도 같은 row 를 두 번 잡지 않습니다 (ShedLock
 * 불필요). 단, 락은 트랜잭션이 닫혀야 풀리므로 *fetch + send + markPublished* 가 같은
 * 트랜잭션 안에서 완결되어야 합니다.</p>
 *
 * <p><b>트랜잭션 경계 — 메시지 단위</b>: 한 트랜잭션이 한 메시지만 다룸. Kafka send 가
 * sendTimeoutMs 까지 블록되더라도 그 트랜잭션 한 개만 영향 — 다른 워커 / 다른 메시지의
 * 트랜잭션은 독립이라 connection pool 전체가 막히지 않습니다. batch_size 만큼 매 poll 마다
 * 메시지를 처리하지만 *각 메시지마다 별도 트랜잭션*.</p>
 *
 * <p>실패 시 markPublished 를 안 하고 트랜잭션을 commit 하므로 (예외가 잡혀서) lock 이
 * 해제되고, 다음 polling 에서 재시도 (at-least-once, 최소 한 번 전달이지만 중복 가능).
 * 계속 실패하는 메시지 (poison pill) 는 별도 백로그로 처리.</p>
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
     * 폴링 진입점 — batchSize 만큼 메시지를 메시지 단위 트랜잭션으로 처리. 한 메시지가
     * 실패해도 격리되어 다음 메시지가 진행됨. SKIP LOCKED 덕분에 다른 워커가 잡고 있는
     * 메시지는 자동으로 제외.
     */
    @Scheduled(fixedDelayString = "${billing.outbox.relay.poll-interval-ms:1000}")
    public void publishPending() {
        int published = 0;
        int attempted = 0;
        for (int i = 0; i < batchSize; i++) {
            // 한 메시지 처리 중 *예상 못한* RuntimeException 이 발생해도 전체 polling 이
            // 죽지 않도록 격리. 다음 polling 에서 같은 메시지를 다시 시도 (at-least-once).
            try {
                ProcessResult result = publishNext();
                if (result == ProcessResult.NONE) break;
                attempted++;
                if (result == ProcessResult.PUBLISHED) published++;
            } catch (RuntimeException e) {
                attempted++;
                log.warn("outbox relay unexpected error skipping", e);
            }
        }
        if (attempted > 0) log.debug("outbox relay published {}/{}", published, attempted);
    }

    private enum ProcessResult { PUBLISHED, FAILED, NONE }

    /**
     * 한 메시지 단위 트랜잭션 — fetch (SKIP LOCKED) + Kafka send + markPublished. send 가
     * 실패하거나 timeout 이면 markPublished 안 됨 → 트랜잭션 commit 후 lock 해제, 다음
     * polling 에서 재시도.
     *
     * <p>SKIP LOCKED 가 동작하려면 fetch 시 잡힌 row lock 이 markPublished 까지 유지되어야
     * 하므로 fetch + send + markPublished 가 *반드시 한 트랜잭션* 이어야 합니다. 트랜잭션이
     * commit 되어야만 다른 워커가 그 row 를 볼 수 있게 됩니다.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProcessResult publishNext() {
        List<OutboxJpaEntity> head = outboxRepository.findUnpublished(PageRequest.of(0, 1));
        if (head.isEmpty()) return ProcessResult.NONE;
        OutboxJpaEntity msg = head.get(0);
        if (sendToKafka(msg)) {
            outboxRepository.markPublished(msg.getId(), clock.instant());
            return ProcessResult.PUBLISHED;
        }
        return ProcessResult.FAILED;
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
