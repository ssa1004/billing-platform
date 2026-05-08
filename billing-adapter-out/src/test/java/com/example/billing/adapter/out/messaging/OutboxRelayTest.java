package com.example.billing.adapter.out.messaging;

import com.example.billing.adapter.out.persistence.outbox.OutboxJpaEntity;
import com.example.billing.adapter.out.persistence.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OutboxRelay 단위 테스트 — 정상 발행 / 부분 실패 / 예상 못한 RuntimeException 격리.
 *
 * <p>핵심 invariant: 한 메시지가 죽어도 같은 batch 의 다른 메시지는 진행되어야 한다.
 * 죽은 메시지는 markPublished 가 안 되므로 다음 polling 에서 재시도 (at-least-once).</p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-05-07T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock OutboxRepository outboxRepository;
    @Mock KafkaTemplate<String, String> kafkaTemplate;

    OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(outboxRepository, kafkaTemplate, CLOCK);
        ReflectionTestUtils.setField(relay, "batchSize", 100);
        ReflectionTestUtils.setField(relay, "sendTimeoutMs", 1000L);
        ReflectionTestUtils.setField(relay, "topicPrefix", "billing.");
    }

    private static OutboxJpaEntity msg(String aggregateType, String eventType) {
        OutboxJpaEntity m = new OutboxJpaEntity();
        m.setId(UUID.randomUUID());
        m.setAggregateType(aggregateType);
        m.setAggregateId("agg-1");
        m.setEventType(eventType);
        m.setPayload("{}");
        m.setCreatedAt(NOW);
        return m;
    }

    @Test
    void emptyBatch_noWork() {
        when(outboxRepository.findUnpublished(any(Pageable.class))).thenReturn(List.of());

        relay.publishPending();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void successfulSend_marksPublished() {
        OutboxJpaEntity m = msg("Order", "Placed");
        when(outboxRepository.findUnpublished(any(Pageable.class))).thenReturn(List.of(m));
        when(kafkaTemplate.send(eq("billing.order.placed"), eq("agg-1"), eq("{}")))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        relay.publishPending();

        verify(outboxRepository).markPublished(eq(m.getId()), eq(NOW));
    }

    @Test
    void runtimeException_isolatedToSingleMessage_otherStillPublishes() {
        OutboxJpaEntity poison = msg("Order", "Placed");
        OutboxJpaEntity good = msg("Order", "Paid");
        when(outboxRepository.findUnpublished(any(Pageable.class))).thenReturn(List.of(poison, good));
        // poison 메시지는 send 시점에 직렬화 / 설정 등으로 RuntimeException
        when(kafkaTemplate.send(eq("billing.order.placed"), eq("agg-1"), eq("{}")))
                .thenThrow(new IllegalStateException("serialization broken"));
        // good 은 정상
        when(kafkaTemplate.send(eq("billing.order.paid"), eq("agg-1"), eq("{}")))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        relay.publishPending();

        // poison 은 markPublished 안 됨 (다음 poll 에서 재시도), good 은 markPublished 됨.
        verify(outboxRepository, never()).markPublished(eq(poison.getId()), any());
        verify(outboxRepository, times(1)).markPublished(eq(good.getId()), eq(NOW));
    }

    @Test
    void kafkaSendReturnsFailedFuture_skipsMarkPublished() {
        OutboxJpaEntity m = msg("Payment", "Approved");
        when(outboxRepository.findUnpublished(any(Pageable.class))).thenReturn(List.of(m));

        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send(eq("billing.payment.approved"), eq("agg-1"), eq("{}")))
                .thenReturn(failed);

        relay.publishPending();

        verify(outboxRepository, never()).markPublished(any(), any());
    }
}
