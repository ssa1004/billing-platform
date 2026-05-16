package com.example.billing.application.port.out

import com.example.billing.domain.invoice.Invoice
import com.example.billing.domain.invoice.InvoiceStatus
import com.example.billing.domain.settlement.BillingPeriod
import com.example.billing.domain.shared.CustomerId
import java.time.Instant
import java.util.Optional
import java.util.UUID

interface InvoiceRepository {

    fun save(invoice: Invoice)

    fun findById(id: UUID): Optional<Invoice>

    fun findBy(customerId: CustomerId, period: BillingPeriod): Optional<Invoice>

    fun findByCustomer(customerId: CustomerId, limit: Int): List<Invoice>

    /**
     * 재시도 후보 결제 청구서를 SKIP LOCKED 로 잡아 반환.
     * 여러 worker 가 동시 호출해도 같은 row 를 두 번 잡지 않는다.
     */
    fun findIssuedForRetryForUpdateSkipLocked(status: InvoiceStatus, limit: Int): List<Invoice>

    /**
     * 미수금 (aged receivables) 조회 — 결제 대기 중이거나 연체된 invoice.
     *
     * 회계 / 신용 관리 / collection workflow 에서 사용. customer 별로 group by 하여
     * 누적 미수 금액 + 가장 오래된 invoice 기준 경과일 (aging bucket: 0-30 / 31-60 /
     * 61-90 / 90+ 일) 계산은 호출 측에서 처리.
     */
    fun findUnpaid(asOf: Instant, limit: Int): List<Invoice>

    /**
     * Soft delete (논리 삭제, ADR-0030). row 자체는 남고 `deleted_at` 만 채워짐. 같은
     * 트랜잭션 안에서 호출하면 audit 와 함께 commit / rollback. 운영 표준은 application
     * service 가 SoftDeleteService 를 통해 호출 — 여기 직접 호출 금지.
     *
     * @param id        삭제 대상 invoice id
     * @param deletedBy 누가 삭제했나 (user / operator id) — null 금지
     * @return 실제로 삭제된 row 가 있으면 true. 없거나 이미 삭제된 row 면 false.
     */
    fun softDelete(id: UUID, deletedBy: String): Boolean

    /**
     * 운영자 화면 전용. `deleted_at` 이 set 된 row 까지 포함해 조회. 일반 도메인 흐름은
     * 절대 사용 금지 — 활성 row 만 본다는 기본 가정 을 깸.
     */
    fun findByIdIncludingDeleted(id: UUID): Optional<Invoice>
}
