package com.example.billing.domain.invoice

import com.example.billing.domain.pricing.PricingSnapshot
import com.example.billing.domain.settlement.BillingPeriod
import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.shared.Money
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 청구서 애그리거트. 한 customer × 한 BillingPeriod (청구 기간) 당 하나만 존재 (유일성은 DB
 * UNIQUE 제약으로 보장).
 *
 * 상태 전이는 [issue], [markPaid], [markOverdue], [cancel] 메서드로만 가능합니다. setter 가
 * 없습니다.
 *
 * 총액은 [InvoiceLine.lineTotal] 의 합. 가격 정책은 [PricingSnapshot] (그 시점 요금표를 그대로
 * 보관한 값 객체) 으로 invoice 자체에 저장하므로, 요금제가 변경되어도 과거 청구서 금액은 변하지
 * 않습니다.
 *
 * **컬렉션 방어적 복사**: [lines] 는 생성자에서 `List.copyOf` 로 불변 복사본을 만들어 보관하고,
 * accessor 는 그 unmodifiable list 를 그대로 반환합니다 — Java 호출자가 받은 리스트를 수정해도
 * 애그리거트 내부 상태가 흔들리지 않습니다 (기존 Java 구현과 동일 의미).
 *
 * record-style accessor (`id()` / `status()` / `lines()` 등) 는 `@get:JvmName` 으로
 * Java/Kotlin 양쪽 호출자 호환 유지.
 */
class Invoice private constructor(
    @get:JvmName("id") val id: UUID,
    @get:JvmName("customerId") val customerId: CustomerId,
    @get:JvmName("period") val period: BillingPeriod,
    lines: List<InvoiceLine>,
    @get:JvmName("total") val total: Money,
    @get:JvmName("pricingSnapshot") val pricingSnapshot: PricingSnapshot,
    @get:JvmName("createdAt") val createdAt: Instant,
    status: InvoiceStatus,
    appliedCredit: Money,
    issuedAt: Instant?,
    dueAt: Instant?,
    paidAt: Instant?,
    @get:JvmName("version") val version: Long,
) {

    /** 불변 방어적 복사본 — 외부에서 받은 리스트 변경이 애그리거트에 새지 않도록. */
    @get:JvmName("lines")
    val lines: List<InvoiceLine> = java.util.List.copyOf(lines)

    @get:JvmName("status")
    var status: InvoiceStatus = status
        private set

    /** 0 ≤ appliedCredit ≤ total */
    @get:JvmName("appliedCredit")
    var appliedCredit: Money = appliedCredit
        private set

    @get:JvmName("issuedAt")
    var issuedAt: Instant? = issuedAt
        private set

    @get:JvmName("dueAt")
    var dueAt: Instant? = dueAt
        private set

    @get:JvmName("paidAt")
    var paidAt: Instant? = paidAt
        private set

    fun issue(clock: Clock) {
        if (status != InvoiceStatus.DRAFT) {
            throw IllegalInvoiceTransitionException(status, InvoiceStatus.ISSUED)
        }
        val now = clock.instant()
        this.status = InvoiceStatus.ISSUED
        this.issuedAt = now
        this.dueAt = now.plus(DEFAULT_DUE_DAYS.toLong(), ChronoUnit.DAYS)
    }

    fun markPaid(clock: Clock) {
        if (status != InvoiceStatus.ISSUED && status != InvoiceStatus.OVERDUE) {
            throw IllegalInvoiceTransitionException(status, InvoiceStatus.PAID)
        }
        this.status = InvoiceStatus.PAID
        this.paidAt = clock.instant()
    }

    fun markOverdue(clock: Clock) {
        if (status != InvoiceStatus.ISSUED) {
            throw IllegalInvoiceTransitionException(status, InvoiceStatus.OVERDUE)
        }
        val due = dueAt
        check(due != null && !clock.instant().isBefore(due)) { "invoice not yet due" }
        this.status = InvoiceStatus.OVERDUE
    }

    fun cancel() {
        if (status.isFinal()) {
            throw IllegalInvoiceTransitionException(status, InvoiceStatus.CANCELLED)
        }
        this.status = InvoiceStatus.CANCELLED
    }

    /**
     * Credit 적용. 결제 대상 금액([amountDue]) 을 줄인다. 음수 / amountDue 초과 / 종착 상태는
     * 거부.
     *
     * amountDue 가 0 이 되면 자동 PAID 전환은 하지 않는다 — 결제 service 가 ledger 와 함께
     * 처리. 여기서는 잔액만 줄임.
     *
     * @return 새 amountDue
     */
    fun applyCredit(amount: Money): Money {
        check(!status.isFinal()) {
            "cannot apply credit to invoice in final state: $status"
        }
        check(status != InvoiceStatus.DRAFT) {
            "cannot apply credit to DRAFT invoice — issue first"
        }
        require(amount.currency == total.currency) {
            "currency mismatch: invoice=${total.currency} credit=${amount.currency}"
        }
        require(amount.isPositive) { "amount must be positive: $amount" }
        val due = amountDue()
        require(amount.compareTo(due) <= 0) {
            "applied credit exceeds amountDue: amount=$amount amountDue=$due"
        }
        this.appliedCredit = appliedCredit.add(amount)
        return amountDue()
    }

    /** 남은 결제 대상 금액 = total - appliedCredit. */
    fun amountDue(): Money = total.subtract(appliedCredit)

    companion object {

        private const val DEFAULT_DUE_DAYS = 14

        @JvmStatic
        fun draft(
            customerId: CustomerId,
            period: BillingPeriod,
            lines: List<InvoiceLine>,
            pricingSnapshot: PricingSnapshot,
            clock: Clock,
        ): Invoice {
            require(lines.isNotEmpty()) { "invoice must have at least one line" }
            val total = lines.map(InvoiceLine::lineTotal).reduce(Money::add)
            return Invoice(
                UUID.randomUUID(), customerId, period, lines, total,
                pricingSnapshot, clock.instant(), InvoiceStatus.DRAFT,
                Money.zero(total.currency),
                null, null, null, 0L,
            )
        }

        @JvmStatic
        fun restore(
            id: UUID,
            customerId: CustomerId,
            period: BillingPeriod,
            lines: List<InvoiceLine>,
            total: Money,
            pricingSnapshot: PricingSnapshot,
            createdAt: Instant,
            status: InvoiceStatus,
            appliedCredit: Money,
            issuedAt: Instant?,
            dueAt: Instant?,
            paidAt: Instant?,
            version: Long,
        ): Invoice = Invoice(
            id, customerId, period, lines, total, pricingSnapshot, createdAt,
            status, appliedCredit, issuedAt, dueAt, paidAt, version,
        )
    }
}
