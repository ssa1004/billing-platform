package com.example.billing.domain.payment;

import com.example.billing.domain.order.OrderId;
import com.example.billing.domain.shared.Money;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Payment 애그리거트.
 *
 * <p>PG 호출의 결과를 보존. {@link #idempotencyKey} 가 unique 하므로 같은 키로 중복 결제 시도 시
 * DB unique constraint 가 보호 (DB-level idempotency). Redis NX 와 함께 두 단계 방어 (ADR-0006).</p>
 */
public class Payment {

    private final PaymentId id;
    private final OrderId orderId;
    private final Money amount;
    private final PaymentMethod method;
    private final String idempotencyKey;
    private PaymentStatus status;
    private String pgTransactionId;
    private String errorCode;
    private String errorMessage;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private Payment(PaymentId id, OrderId orderId, Money amount, PaymentMethod method,
                    String idempotencyKey, PaymentStatus status, String pgTransactionId,
                    String errorCode, String errorMessage,
                    Instant createdAt, Instant updatedAt, long version) {
        this.id = id;
        this.orderId = orderId;
        this.amount = amount;
        this.method = method;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.pgTransactionId = pgTransactionId;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static Payment initiate(OrderId orderId, Money amount, PaymentMethod method,
                                   String idempotencyKey, Clock clock) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) throw new IllegalArgumentException("amount must be positive");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey must not be blank");
        Instant now = clock.instant();
        return new Payment(PaymentId.newId(), orderId, amount, method, idempotencyKey,
                PaymentStatus.PENDING, null, null, null, now, now, 0L);
    }

    /** 영속 계층에서 복원. */
    public static Payment restore(PaymentId id, OrderId orderId, Money amount, PaymentMethod method,
                                  String idempotencyKey, PaymentStatus status, String pgTransactionId,
                                  String errorCode, String errorMessage,
                                  Instant createdAt, Instant updatedAt, long version) {
        return new Payment(id, orderId, amount, method, idempotencyKey, status,
                pgTransactionId, errorCode, errorMessage, createdAt, updatedAt, version);
    }

    public PaymentEvents.PaymentApproved approve(String pgTransactionId, Clock clock) {
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException("payment must be PENDING to approve, was " + status);
        }
        this.status = PaymentStatus.APPROVED;
        this.pgTransactionId = pgTransactionId;
        this.updatedAt = clock.instant();
        return new PaymentEvents.PaymentApproved(id, orderId, amount, pgTransactionId, updatedAt);
    }

    public PaymentEvents.PaymentRejected reject(String errorCode, String errorMessage, Clock clock) {
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException("payment must be PENDING to reject, was " + status);
        }
        this.status = PaymentStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.updatedAt = clock.instant();
        return new PaymentEvents.PaymentRejected(id, orderId, amount, errorCode, errorMessage, updatedAt);
    }

    // Getters
    public PaymentId id() { return id; }
    public OrderId orderId() { return orderId; }
    public Money amount() { return amount; }
    public PaymentMethod method() { return method; }
    public String idempotencyKey() { return idempotencyKey; }
    public PaymentStatus status() { return status; }
    public String pgTransactionId() { return pgTransactionId; }
    public String errorCode() { return errorCode; }
    public String errorMessage() { return errorMessage; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
