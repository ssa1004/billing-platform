package com.example.billing.adapter.out.persistence.jpa.repository;

import com.example.billing.adapter.out.persistence.jpa.entity.PaymentJpaEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataPaymentRepository extends JpaRepository<PaymentJpaEntity, UUID> {
    Optional<PaymentJpaEntity> findByIdempotencyKey(String key);

    /**
     * Reconciler 가 호출. PENDING 으로 stale 된 Payment 들을 오래된 순으로.
     * (status, created_at) 인덱스를 사용한다.
     */
    @Query("SELECT p FROM PaymentJpaEntity p "
            + "WHERE p.status = 'PENDING' AND p.createdAt <= :before "
            + "ORDER BY p.createdAt ASC")
    List<PaymentJpaEntity> findStalePending(@Param("before") Instant before, Pageable pageable);
}
