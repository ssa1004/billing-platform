package com.example.billing.adapter.out.persistence.jpa.repository

import com.example.billing.adapter.out.persistence.jpa.entity.UsageEventJpaEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface SpringDataUsageEventRepository : JpaRepository<UsageEventJpaEntity, UUID> {

    fun findByReceivedAtBetweenOrderByReceivedAt(
        fromInclusive: Instant,
        toExclusive: Instant,
        pageable: Pageable,
    ): List<UsageEventJpaEntity>
}
