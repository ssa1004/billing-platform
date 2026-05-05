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

    /**
     * 미수금 (aged receivables) 조회 — 결제 대기 중이거나 연체된 invoice.
     *
     * <p>회계 / 신용 관리 / collection workflow 에서 사용. customer 별로 group by 하여
     * 누적 미수 금액 + 가장 오래된 invoice 기준 경과일 (aging bucket: 0-30 / 31-60 /
     * 61-90 / 90+ 일) 계산은 호출 측에서 처리.</p>
     */
    List<Invoice> findUnpaid(java.time.Instant asOf, int limit);
}
