package com.example.wallet.adapter.out.persistence.jpa.repository;

import com.example.wallet.adapter.out.persistence.jpa.entity.RefundJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SpringDataRefundRepository extends JpaRepository<RefundJpaEntity, UUID> {
}
