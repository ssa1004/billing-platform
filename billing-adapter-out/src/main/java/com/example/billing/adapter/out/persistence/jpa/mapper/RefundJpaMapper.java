package com.example.billing.adapter.out.persistence.jpa.mapper;

import com.example.billing.adapter.out.persistence.jpa.entity.RefundJpaEntity;
import com.example.billing.domain.payment.PaymentId;
import com.example.billing.domain.refund.Refund;
import com.example.billing.domain.refund.RefundId;
import com.example.billing.domain.refund.RefundStatus;
import com.example.billing.domain.shared.Money;

import java.util.Currency;

public final class RefundJpaMapper {

    private RefundJpaMapper() {}

    public static RefundJpaEntity toEntity(Refund r) {
        RefundJpaEntity e = new RefundJpaEntity();
        e.setId(r.id().value());
        e.setPaymentId(r.paymentId().value());
        e.setAmount(r.amount().amount());
        e.setCurrency(r.amount().currency().getCurrencyCode());
        e.setReason(r.reason());
        e.setIdempotencyKey(r.idempotencyKey());
        e.setStatus(r.status().name());
        e.setPgRefundId(r.pgRefundId());
        e.setRequestedAt(r.requestedAt());
        e.setCompletedAt(r.completedAt());
        e.setVersion(r.version());
        return e;
    }

    public static Refund toDomain(RefundJpaEntity e) {
        Currency currency = Currency.getInstance(e.getCurrency());
        return Refund.restore(
                new RefundId(e.getId()),
                new PaymentId(e.getPaymentId()),
                Money.of(e.getAmount(), currency),
                e.getReason(),
                e.getIdempotencyKey(),
                RefundStatus.valueOf(e.getStatus()),
                e.getPgRefundId(),
                e.getRequestedAt(),
                e.getCompletedAt(),
                e.getVersion()
        );
    }
}
