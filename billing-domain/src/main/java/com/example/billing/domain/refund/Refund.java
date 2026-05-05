package com.example.billing.domain.refund;

import com.example.billing.domain.payment.PaymentId;
import com.example.billing.domain.shared.DomainEvent;
import com.example.billing.domain.shared.Money;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Refund 애그리거트. 환불 요청 → PG 환불 호출 → Wallet 환원 흐름의 상태 보존.
 */
public class Refund {

    private final RefundId id;
    private final PaymentId paymentId;
    private final Money amount;
    private final String reason;
    private RefundStatus status;
    private String pgRefundId;
    private final Instant requestedAt;
    private Instant completedAt;
    private long version;

    private Refund(RefundId id, PaymentId paymentId, Money amount, String reason,
                   RefundStatus status, String pgRefundId,
                   Instant requestedAt, Instant completedAt, long version) {
        this.id = id;
        this.paymentId = paymentId;
        this.amount = amount;
        this.reason = reason;
        this.status = status;
        this.pgRefundId = pgRefundId;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
        this.version = version;
    }

    public static Refund request(PaymentId paymentId, Money amount, String reason, Clock clock) {
        Objects.requireNonNull(paymentId);
        Objects.requireNonNull(amount);
        if (!amount.isPositive()) throw new IllegalArgumentException("amount must be positive");
        return new Refund(RefundId.newId(), paymentId, amount, reason,
                RefundStatus.REQUESTED, null, clock.instant(), null, 0L);
    }

    /** 영속 계층에서 복원. */
    public static Refund restore(RefundId id, PaymentId paymentId, Money amount, String reason,
                                 RefundStatus status, String pgRefundId,
                                 Instant requestedAt, Instant completedAt, long version) {
        return new Refund(id, paymentId, amount, reason, status, pgRefundId,
                requestedAt, completedAt, version);
    }

    public RefundApproved approve(String pgRefundId, Clock clock) {
        if (status != RefundStatus.REQUESTED) {
            throw new IllegalStateException("refund must be REQUESTED to approve, was " + status);
        }
        this.status = RefundStatus.APPROVED;
        this.pgRefundId = pgRefundId;
        return new RefundApproved(id, paymentId, amount, pgRefundId, clock.instant());
    }

    public RefundCompleted complete(Clock clock) {
        if (status != RefundStatus.APPROVED) {
            throw new IllegalStateException("refund must be APPROVED to complete, was " + status);
        }
        this.status = RefundStatus.COMPLETED;
        this.completedAt = clock.instant();
        return new RefundCompleted(id, paymentId, amount, completedAt);
    }

    public RefundFailed fail(String reason, Clock clock) {
        if (status.isTerminal()) {
            throw new IllegalStateException("refund already terminal: " + status);
        }
        this.status = RefundStatus.FAILED;
        this.completedAt = clock.instant();
        return new RefundFailed(id, paymentId, reason, completedAt);
    }

    public RefundId id() { return id; }
    public PaymentId paymentId() { return paymentId; }
    public Money amount() { return amount; }
    public String reason() { return reason; }
    public RefundStatus status() { return status; }
    public String pgRefundId() { return pgRefundId; }
    public Instant requestedAt() { return requestedAt; }
    public Instant completedAt() { return completedAt; }
    public long version() { return version; }

    public record RefundApproved(RefundId refundId, PaymentId paymentId, Money amount,
                                 String pgRefundId, Instant occurredAt) implements DomainEvent {
        @Override public String aggregateId() { return refundId.toString(); }
    }

    public record RefundCompleted(RefundId refundId, PaymentId paymentId, Money amount,
                                  Instant occurredAt) implements DomainEvent {
        @Override public String aggregateId() { return refundId.toString(); }
    }

    public record RefundFailed(RefundId refundId, PaymentId paymentId, String reason,
                               Instant occurredAt) implements DomainEvent {
        @Override public String aggregateId() { return refundId.toString(); }
    }
}
