package com.example.billing.domain.shared;

import java.time.Instant;

/**
 * 도메인 이벤트 marker interface. 모든 이벤트는 sealed 트리에 속한다 — 외부 시스템과의 이벤트
 * 컨트랙트 안정성 + JSON 직렬화 시 type discrimination 용이.
 */
public interface DomainEvent {

    String aggregateId();

    Instant occurredAt();
}
