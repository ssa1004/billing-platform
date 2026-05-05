package com.example.billing.application.port.out;

import com.example.billing.domain.shared.DomainEvent;

/**
 * 도메인 이벤트 발행 port. 구현체는 Outbox INSERT (트랜잭션 안전) — Kafka 직접 produce 가 아님.
 * Outbox relay 가 Kafka 로 밀어넣음 (ADR-0005).
 */
public interface EventPublisher {

    void publish(DomainEvent event);
}
