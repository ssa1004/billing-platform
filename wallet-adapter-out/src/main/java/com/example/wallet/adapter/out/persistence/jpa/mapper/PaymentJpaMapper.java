package com.example.wallet.adapter.out.persistence.jpa.mapper;

import com.example.wallet.adapter.out.persistence.jpa.entity.PaymentJpaEntity;
import com.example.wallet.domain.order.OrderId;
import com.example.wallet.domain.payment.Payment;
import com.example.wallet.domain.payment.PaymentId;
import com.example.wallet.domain.payment.PaymentMethod;
import com.example.wallet.domain.payment.PaymentStatus;
import com.example.wallet.domain.shared.Money;

import java.util.Currency;

public final class PaymentJpaMapper {

    private PaymentJpaMapper() {}

    public static PaymentJpaEntity toEntity(Payment p) {
        PaymentJpaEntity e = new PaymentJpaEntity();
        e.setId(p.id().value());
        e.setOrderId(p.orderId().value());
        e.setAmount(p.amount().amount());
        e.setCurrency(p.amount().currency().getCurrencyCode());
        e.setMethod(p.method().name());
        e.setStatus(p.status().name());
        e.setPgTransactionId(p.pgTransactionId());
        e.setIdempotencyKey(p.idempotencyKey());
        e.setErrorCode(p.errorCode());
        e.setErrorMessage(p.errorMessage());
        e.setCreatedAt(p.createdAt());
        e.setUpdatedAt(p.updatedAt());
        e.setVersion(p.version());
        return e;
    }

    public static Payment toDomain(PaymentJpaEntity e) {
        Currency currency = Currency.getInstance(e.getCurrency());
        return Payment.restore(
                new PaymentId(e.getId()),
                new OrderId(e.getOrderId()),
                Money.of(e.getAmount(), currency),
                PaymentMethod.valueOf(e.getMethod()),
                e.getIdempotencyKey(),
                PaymentStatus.valueOf(e.getStatus()),
                e.getPgTransactionId(),
                e.getErrorCode(),
                e.getErrorMessage(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getVersion()
        );
    }
}
