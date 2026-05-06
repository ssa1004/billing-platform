package com.example.billing.adapter.out.persistence.jpa.repository;

import com.example.billing.adapter.out.persistence.jpa.entity.WebhookDeliveryJpaEntity;
import com.example.billing.domain.webhook.WebhookDeliveryStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringDataWebhookDeliveryRepository extends JpaRepository<WebhookDeliveryJpaEntity, UUID> {

    /**
     * 워커 픽업 query — PENDING 이면서 next_attempt_at 도달한 것들을 SKIP LOCKED 로 잠금.
     *
     * <p>여러 워커 인스턴스가 동시에 호출해도 같은 row 를 두 번 잡지 않는다 (PG 의 SKIP LOCKED
     * 가 lock 못 잡은 row 는 그냥 결과에서 빼버림). H2 는 SKIP LOCKED 를 무시하지만 단일
     * 워커 dev 환경에선 문제 없음.</p>
     *
     * <p>{@code jakarta.persistence.lock.timeout = 0} → lock 못 잡으면 즉시 다음 row 시도.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"),
            @QueryHint(name = "org.hibernate.lockOptions.followOnLocking", value = "false")
    })
    @Query("""
            SELECT d FROM WebhookDeliveryJpaEntity d
             WHERE d.status = com.example.billing.domain.webhook.WebhookDeliveryStatus.PENDING
               AND d.nextAttemptAt <= :now
             ORDER BY d.nextAttemptAt ASC
            """)
    List<WebhookDeliveryJpaEntity> claimPending(@Param("now") Instant now, Pageable pageable);

    List<WebhookDeliveryJpaEntity> findByEndpointIdOrderByCreatedAtDesc(UUID endpointId, Pageable pageable);

    List<WebhookDeliveryJpaEntity> findByStatusOrderByCreatedAtDesc(WebhookDeliveryStatus status, Pageable pageable);
}
