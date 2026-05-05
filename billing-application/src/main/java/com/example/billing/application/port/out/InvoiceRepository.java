package com.example.billing.application.port.out;

import com.example.billing.domain.invoice.Invoice;
import com.example.billing.domain.invoice.InvoiceStatus;
import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository {

    void save(Invoice invoice);

    Optional<Invoice> findById(UUID id);

    Optional<Invoice> findBy(CustomerId customerId, BillingPeriod period);

    List<Invoice> findByCustomer(CustomerId customerId, int limit);

    /**
     * 재시도 후보 결제 청구서를 SKIP LOCKED 로 잡아 반환.
     * 여러 worker 가 동시 호출해도 같은 row 를 두 번 잡지 않는다.
     */
    List<Invoice> findIssuedForRetryForUpdateSkipLocked(InvoiceStatus status, int limit);
}
