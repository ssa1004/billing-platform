package com.example.billing.domain.credit;

import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.DomainEvent;
import com.example.billing.domain.shared.Money;
import com.example.billing.domain.shared.Reference;

import java.time.Instant;

/**
 * Credit 도메인 이벤트.
 *
 * <p>모든 잔액 변경 메서드는 {@link Credit} 가 직접 상태를 바꾸고 해당 이벤트를 반환한다.
 * 호출 측 (application service) 가 Outbox 에 기록 → Kafka publish.</p>
 */
public final class CreditEvents {

    private CreditEvents() {}

    public record CreditGranted(
            CreditId creditId,
            CustomerId customerId,
            CreditType type,
            Money grantedAmount,
            Instant validFrom,
            Instant validUntil,   // nullable = 만료 없음
            Instant occurredAt
    ) implements DomainEvent {
        @Override public String aggregateId() { return creditId.toString(); }
    }

    public record CreditConsumed(
            CreditId creditId,
            CustomerId customerId,
            Money consumedAmount,
            Money remainingBalance,
            Reference reference,  // 보통 InvoiceId
            Instant occurredAt
    ) implements DomainEvent {
        @Override public String aggregateId() { return creditId.toString(); }
    }

    public record CreditExhausted(
            CreditId creditId,
            CustomerId customerId,
            Instant occurredAt
    ) implements DomainEvent {
        @Override public String aggregateId() { return creditId.toString(); }
    }

    public record CreditExpired(
            CreditId creditId,
            CustomerId customerId,
            Money forfeitedBalance,   // 만료 시점에 남아 있던 잔액
            Instant occurredAt
    ) implements DomainEvent {
        @Override public String aggregateId() { return creditId.toString(); }
    }

    public record CreditRevoked(
            CreditId creditId,
            CustomerId customerId,
            Money revokedBalance,
            String reason,
            Instant occurredAt
    ) implements DomainEvent {
        @Override public String aggregateId() { return creditId.toString(); }
    }
}
