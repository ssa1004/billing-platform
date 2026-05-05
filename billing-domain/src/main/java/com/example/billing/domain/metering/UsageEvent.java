package com.example.billing.domain.metering;

import com.example.billing.domain.shared.CustomerId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 사용량 이벤트 — append-only, immutable.
 *
 * <p>외부 (API 게이트웨이, SDK) 에서 푸시하는 측정 이벤트. eventId 는 클라이언트가 생성하며
 * 멱등성 키 역할을 한다 (동일 eventId 재수신 시 무시).</p>
 *
 * <p>{@link ResourceType} 별로 unit 이 정해져 있다 (API 호출은 count, 스토리지는 byte_hour 등).
 * 집계 단계에서 unit 별로 합산되어 {@link AggregatedUsage} 로 변환된다.</p>
 */
public final class UsageEvent {

    private final UUID eventId;
    private final CustomerId customerId;
    private final ResourceType resourceType;
    private final long quantity;
    private final Instant occurredAt;
    private final Instant receivedAt;

    private UsageEvent(UUID eventId, CustomerId customerId, ResourceType resourceType,
                       long quantity, Instant occurredAt, Instant receivedAt) {
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be non-negative: " + quantity);
        }
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
        this.quantity = quantity;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
    }

    public static UsageEvent record(UUID eventId, CustomerId customerId, ResourceType resourceType,
                                    long quantity, Instant occurredAt, Instant receivedAt) {
        return new UsageEvent(eventId, customerId, resourceType, quantity, occurredAt, receivedAt);
    }

    public static UsageEvent restore(UUID eventId, CustomerId customerId, ResourceType resourceType,
                                     long quantity, Instant occurredAt, Instant receivedAt) {
        return new UsageEvent(eventId, customerId, resourceType, quantity, occurredAt, receivedAt);
    }

    public UUID eventId() { return eventId; }
    public CustomerId customerId() { return customerId; }
    public ResourceType resourceType() { return resourceType; }
    public long quantity() { return quantity; }
    public Instant occurredAt() { return occurredAt; }
    public Instant receivedAt() { return receivedAt; }
}
