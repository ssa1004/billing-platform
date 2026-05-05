package com.example.billing.adapter.out.persistence.jpa.repository;

import com.example.billing.adapter.out.persistence.jpa.entity.AggregatedUsageJpaEntity;
import com.example.billing.domain.metering.ResourceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataAggregatedUsageRepository extends JpaRepository<AggregatedUsageJpaEntity, UUID> {

    Optional<AggregatedUsageJpaEntity> findByCustomerIdAndResourceTypeAndPeriodYearMonth(
            String customerId, ResourceType resourceType, String periodYearMonth);

    List<AggregatedUsageJpaEntity> findByCustomerIdAndPeriodYearMonth(
            String customerId, String periodYearMonth);
}
