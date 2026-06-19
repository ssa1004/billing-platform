package com.example.billing.adapter.out.persistence.jpa.repository

import com.example.billing.adapter.out.persistence.jpa.entity.UsageEventJpaEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface SpringDataUsageEventRepository : JpaRepository<UsageEventJpaEntity, UUID> {

    /**
     * 청구 기간 집계용 조회 — 사용량이 **발생한 시각**(`occurredAt`) 기준으로 `[from, to)`
     * 구간을 조회한다. 서버 수신 시각(`receivedAt`)이 아니라 발생 시각으로 거르는 이유:
     * 늦게 도착(backfill)한 이벤트도 실제 사용이 일어난 월에 청구되어야 정확하다.
     */
    fun findByOccurredAtBetweenOrderByOccurredAt(
        fromInclusive: Instant,
        toExclusive: Instant,
        pageable: Pageable,
    ): List<UsageEventJpaEntity>
}
