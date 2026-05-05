package com.example.billing.adapter.out.persistence.jpa.repository;

import com.example.billing.adapter.out.persistence.jpa.entity.UsageEventJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringDataUsageEventRepository extends JpaRepository<UsageEventJpaEntity, UUID> {

    List<UsageEventJpaEntity> findByReceivedAtBetweenOrderByReceivedAt(
            Instant fromInclusive, Instant toExclusive,
            org.springframework.data.domain.Pageable pageable);
}
