package com.example.billing.adapter.out.persistence.jpa.repository;

import com.example.billing.adapter.out.persistence.jpa.entity.InvoiceJpaEntity;
import com.example.billing.domain.invoice.InvoiceStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Aged receivables 조회 — {@link SpringDataInvoiceRepository} 가 추가로 implement.
 *
 * <p>별도 interface 로 둔 이유: aged 조회는 read-only + 운영 화면용이라 main repository
 * 와 책임을 분리. mixin 형태로 합칠 수 있음.</p>
 */
public interface SpringDataInvoiceRepositoryAged {

    @Query("""
            SELECT i FROM InvoiceJpaEntity i
             WHERE i.status IN :unpaid
             ORDER BY i.dueAt ASC NULLS FIRST
            """)
    List<InvoiceJpaEntity> findUnpaidAsOf(@Param("unpaid") List<InvoiceStatus> unpaid,
                                          Pageable pageable);
}
