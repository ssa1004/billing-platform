package com.example.billing.adapter.out.messaging;

import com.example.billing.adapter.out.persistence.outbox.OutboxJpaEntity;
import com.example.billing.adapter.out.persistence.outbox.OutboxRepository;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.domain.shared.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * EventPublisher 의 outbox 구현. 도메인 트랜잭션 안에서 outbox 테이블에 INSERT 만 합니다 —
 * Kafka publish 는 별도로 {@link OutboxRelay} 가 처리합니다. 이렇게 분리해야 DB commit 과
 * 이벤트 발행이 한 트랜잭션에서 묶여 안전합니다 (ADR-0005 참조).
 */
@Component
@RequiredArgsConstructor
public class OutboxEventPublisher implements EventPublisher {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public void publish(DomainEvent event) {
        try {
            String aggregateType = inferAggregateType(event);
            OutboxJpaEntity msg = OutboxJpaEntity.builder()
                    .id(UUID.randomUUID())
                    .aggregateType(aggregateType)
                    .aggregateId(event.aggregateId())
                    .eventType(event.getClass().getSimpleName())
                    .payload(objectMapper.writeValueAsString(event))
                    .createdAt(clock.instant())
                    .build();
            outboxRepository.save(msg);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize event " + event.getClass().getName(), e);
        }
    }

    private String inferAggregateType(DomainEvent event) {
        // 이벤트 클래스 이름으로 aggregate 타입 추론 (예: WalletEvents$WalletDeposited → Wallet)
        String fqn = event.getClass().getName();
        if (fqn.contains(".wallet.")) return "Wallet";
        if (fqn.contains(".order.")) return "Order";
        if (fqn.contains(".payment.")) return "Payment";
        if (fqn.contains(".refund.")) return "Refund";
        return "Unknown";
    }
}
