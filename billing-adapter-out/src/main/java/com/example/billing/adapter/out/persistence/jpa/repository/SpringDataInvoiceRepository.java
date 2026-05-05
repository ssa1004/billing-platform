package com.example.billing.adapter.out.persistence.jpa.repository;

import com.example.billing.adapter.out.persistence.jpa.entity.InvoiceJpaEntity;
import com.example.billing.domain.invoice.InvoiceStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataInvoiceRepository extends JpaRepository<InvoiceJpaEntity, UUID>,
        SpringDataInvoiceRepositoryAged {

    Optional<InvoiceJpaEntity> findByCustomerIdAndPeriodYearMonth(
            String customerId, String periodYearMonth);

    List<InvoiceJpaEntity> findByCustomerIdOrderByPeriodYearMonthDesc(
            String customerId, Pageable pageable);

    /**
     * 결제 재시도 후보를 가져옴. SKIP LOCKED 로 worker pool 이 같은 invoice 를 두 번 잡지
     * 않도록 보장. PostgreSQL 전용 (H2 는 SKIP LOCKED 미지원이므로 dev 에선 fallback).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")  // SKIP_LOCKED hint
    })
    @Query("SELECT i FROM InvoiceJpaEntity i WHERE i.status = :status ORDER BY i.dueAt")
    List<InvoiceJpaEntity> findForRetryWithLock(
            @Param("status") InvoiceStatus status, Pageable pageable);
}
