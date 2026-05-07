package com.example.billing.domain.invoice;

import com.example.billing.domain.pricing.PricingSnapshot;
import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 청구서 애그리거트. 한 customer × 한 BillingPeriod (청구 기간) 당 하나만 존재
 * (유일성은 DB UNIQUE 제약으로 보장).
 *
 * <p>상태 전이는 {@link #issue}, {@link #markPaid}, {@link #markOverdue}, {@link #cancel}
 * 메서드로만 가능합니다. setter 가 없습니다.</p>
 *
 * <p>총액은 {@link InvoiceLine#lineTotal} 의 합. 가격 정책은 {@link PricingSnapshot} (그
 * 시점 요금표를 통째로 박제한 값) 으로 invoice 자체에 저장하므로, 요금제가 변경되어도 과거
 * 청구서 금액은 변하지 않습니다.</p>
 */
public final class Invoice {

    private static final int DEFAULT_DUE_DAYS = 14;

    private final UUID id;
    private final CustomerId customerId;
    private final BillingPeriod period;
    private final List<InvoiceLine> lines;
    private final Money total;
    private final PricingSnapshot pricingSnapshot;
    private final Instant createdAt;
    private InvoiceStatus status;
    private Money appliedCredit;        // 0 ≤ appliedCredit ≤ total
    private Instant issuedAt;
    private Instant dueAt;
    private Instant paidAt;
    private long version;

    private Invoice(UUID id, CustomerId customerId, BillingPeriod period, List<InvoiceLine> lines,
                    Money total, PricingSnapshot pricingSnapshot, Instant createdAt,
                    InvoiceStatus status, Money appliedCredit,
                    Instant issuedAt, Instant dueAt, Instant paidAt,
                    long version) {
        this.id = Objects.requireNonNull(id);
        this.customerId = Objects.requireNonNull(customerId);
        this.period = Objects.requireNonNull(period);
        this.lines = List.copyOf(Objects.requireNonNull(lines));
        this.total = Objects.requireNonNull(total);
        this.pricingSnapshot = Objects.requireNonNull(pricingSnapshot);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.status = Objects.requireNonNull(status);
        this.appliedCredit = Objects.requireNonNull(appliedCredit);
        this.issuedAt = issuedAt;
        this.dueAt = dueAt;
        this.paidAt = paidAt;
        this.version = version;
    }

    public static Invoice draft(CustomerId customerId, BillingPeriod period,
                                List<InvoiceLine> lines, PricingSnapshot pricingSnapshot,
                                Clock clock) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("invoice must have at least one line");
        }
        Money total = lines.stream()
                .map(InvoiceLine::lineTotal)
                .reduce(Money::add)
                .orElseThrow();
        return new Invoice(UUID.randomUUID(), customerId, period, lines, total,
                pricingSnapshot, clock.instant(), InvoiceStatus.DRAFT,
                Money.zero(total.currency()),
                null, null, null, 0L);
    }

    public static Invoice restore(UUID id, CustomerId customerId, BillingPeriod period,
                                  List<InvoiceLine> lines, Money total,
                                  PricingSnapshot pricingSnapshot, Instant createdAt,
                                  InvoiceStatus status, Money appliedCredit,
                                  Instant issuedAt, Instant dueAt,
                                  Instant paidAt, long version) {
        return new Invoice(id, customerId, period, lines, total, pricingSnapshot, createdAt,
                status, appliedCredit, issuedAt, dueAt, paidAt, version);
    }

    public void issue(Clock clock) {
        if (status != InvoiceStatus.DRAFT) {
            throw new IllegalInvoiceTransitionException(status, InvoiceStatus.ISSUED);
        }
        Instant now = clock.instant();
        this.status = InvoiceStatus.ISSUED;
        this.issuedAt = now;
        this.dueAt = now.plus(DEFAULT_DUE_DAYS, ChronoUnit.DAYS);
    }

    public void markPaid(Clock clock) {
        if (status != InvoiceStatus.ISSUED && status != InvoiceStatus.OVERDUE) {
            throw new IllegalInvoiceTransitionException(status, InvoiceStatus.PAID);
        }
        this.status = InvoiceStatus.PAID;
        this.paidAt = clock.instant();
    }

    public void markOverdue(Clock clock) {
        if (status != InvoiceStatus.ISSUED) {
            throw new IllegalInvoiceTransitionException(status, InvoiceStatus.OVERDUE);
        }
        if (dueAt == null || clock.instant().isBefore(dueAt)) {
            throw new IllegalStateException("invoice not yet due");
        }
        this.status = InvoiceStatus.OVERDUE;
    }

    public void cancel() {
        if (status.isFinal()) {
            throw new IllegalInvoiceTransitionException(status, InvoiceStatus.CANCELLED);
        }
        this.status = InvoiceStatus.CANCELLED;
    }

    /**
     * Credit 적용. 결제 대상 금액({@link #amountDue}) 을 줄인다. 음수 / amountDue 초과 / 종착
     * 상태는 거부.
     *
     * <p>amountDue 가 0 이 되면 자동 PAID 전환은 하지 않는다 — 결제 service 가 ledger 와 함께
     * 처리. 여기서는 잔액만 줄임.</p>
     *
     * @return 새 amountDue
     */
    public Money applyCredit(Money amount) {
        if (status.isFinal()) {
            throw new IllegalStateException("cannot apply credit to invoice in final state: " + status);
        }
        if (status == InvoiceStatus.DRAFT) {
            throw new IllegalStateException("cannot apply credit to DRAFT invoice — issue first");
        }
        if (!amount.currency().equals(total.currency())) {
            throw new IllegalArgumentException(
                    "currency mismatch: invoice=" + total.currency() + " credit=" + amount.currency());
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        Money due = amountDue();
        if (amount.compareTo(due) > 0) {
            throw new IllegalArgumentException(
                    "applied credit exceeds amountDue: amount=" + amount + " amountDue=" + due);
        }
        this.appliedCredit = appliedCredit.add(amount);
        return amountDue();
    }

    /** 남은 결제 대상 금액 = total - appliedCredit. */
    public Money amountDue() {
        return total.subtract(appliedCredit);
    }

    public UUID id() { return id; }
    public CustomerId customerId() { return customerId; }
    public BillingPeriod period() { return period; }
    public List<InvoiceLine> lines() { return lines; }
    public Money total() { return total; }
    public Money appliedCredit() { return appliedCredit; }
    public PricingSnapshot pricingSnapshot() { return pricingSnapshot; }
    public Instant createdAt() { return createdAt; }
    public InvoiceStatus status() { return status; }
    public Instant issuedAt() { return issuedAt; }
    public Instant dueAt() { return dueAt; }
    public Instant paidAt() { return paidAt; }
    public long version() { return version; }
}
