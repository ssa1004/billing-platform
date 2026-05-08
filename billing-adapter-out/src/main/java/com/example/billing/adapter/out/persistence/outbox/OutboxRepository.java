package com.example.billing.adapter.out.persistence.outbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxJpaEntity, UUID> {

    /**
     * 미발행 메시지 픽업 — SKIP LOCKED 로 잠긴 row 는 건너뛰어 다른 워커가 같은 메시지를
     * 두 번 잡지 않도록 함. 여러 인스턴스에서 동시에 polling 해도 안전 (ShedLock 필요 없음).
     *
     * <p>{@code jakarta.persistence.lock.timeout = 0} → lock 못 잡으면 즉시 다음 row 로.
     * H2 는 SKIP LOCKED 무시 (단일 워커 dev 환경에선 문제 없음).</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"),
            @QueryHint(name = "org.hibernate.lockOptions.followOnLocking", value = "false")
    })
    @Query("SELECT m FROM OutboxJpaEntity m WHERE m.publishedAt IS NULL ORDER BY m.createdAt ASC")
    List<OutboxJpaEntity> findUnpublished(Pageable pageable);

    @Modifying
    @Query("UPDATE OutboxJpaEntity m SET m.publishedAt = :now WHERE m.id = :id")
    void markPublished(@Param("id") UUID id, @Param("now") Instant now);
}
