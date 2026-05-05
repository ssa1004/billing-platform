package com.example.billing.adapter.out.persistence.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxJpaEntity, UUID> {

    @Query("SELECT m FROM OutboxJpaEntity m WHERE m.publishedAt IS NULL ORDER BY m.createdAt ASC")
    List<OutboxJpaEntity> findUnpublished(Pageable pageable);

    @Modifying
    @Query("UPDATE OutboxJpaEntity m SET m.publishedAt = :now WHERE m.id = :id")
    void markPublished(@Param("id") UUID id, @Param("now") Instant now);
}
