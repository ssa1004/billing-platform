package com.example.billing.adapter.out.persistence.jpa;

import com.example.billing.adapter.out.persistence.jpa.entity.UsageEventJpaEntity;
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataUsageEventRepository;
import com.example.billing.application.port.out.UsageEventRepository;
import com.example.billing.domain.metering.UsageEvent;
import com.example.billing.domain.shared.CustomerId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JpaUsageEventRepositoryAdapter implements UsageEventRepository {

    private final SpringDataUsageEventRepository jpa;

    public JpaUsageEventRepositoryAdapter(SpringDataUsageEventRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean saveIfAbsent(UsageEvent event) {
        if (jpa.existsById(event.eventId())) {
            return false;
        }
        try {
            jpa.save(toEntity(event));
            return true;
        } catch (DataIntegrityViolationException e) {
            // 동시 INSERT race — UNIQUE constraint 가 잡음. 멱등성으로 무시
            return false;
        }
    }

    @Override
    public boolean existsById(UUID eventId) {
        return jpa.existsById(eventId);
    }

    @Override
    public List<UsageEvent> findInRange(Instant fromInclusive, Instant toExclusive, int limit) {
        return jpa.findByReceivedAtBetweenOrderByReceivedAt(fromInclusive, toExclusive,
                        PageRequest.of(0, limit))
                .stream().map(this::toDomain).toList();
    }

    private UsageEventJpaEntity toEntity(UsageEvent e) {
        UsageEventJpaEntity entity = new UsageEventJpaEntity();
        entity.setEventId(e.eventId());
        entity.setCustomerId(e.customerId().value());
        entity.setResourceType(e.resourceType());
        entity.setQuantity(e.quantity());
        entity.setOccurredAt(e.occurredAt());
        entity.setReceivedAt(e.receivedAt());
        return entity;
    }

    private UsageEvent toDomain(UsageEventJpaEntity entity) {
        return UsageEvent.restore(
                entity.getEventId(),
                CustomerId.of(entity.getCustomerId()),
                entity.getResourceType(),
                entity.getQuantity(),
                entity.getOccurredAt(),
                entity.getReceivedAt());
    }
}
