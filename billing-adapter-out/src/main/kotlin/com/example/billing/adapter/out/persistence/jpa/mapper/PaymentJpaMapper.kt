package com.example.billing.adapter.out.persistence.jpa.mapper

import com.example.billing.adapter.out.persistence.jpa.entity.PaymentJpaEntity
import com.example.billing.domain.order.OrderId
import com.example.billing.domain.payment.Payment
import com.example.billing.domain.payment.PaymentId
import com.example.billing.domain.payment.PaymentMethod
import com.example.billing.domain.payment.PaymentStatus
import com.example.billing.domain.shared.Money
import java.util.Currency

object PaymentJpaMapper {

    @JvmStatic
    fun toEntity(p: Payment): PaymentJpaEntity {
        val e = PaymentJpaEntity()
        e.id = p.id.value
        e.orderId = p.orderId.value
        e.amount = p.amount.amount
        e.currency = p.amount.currency.currencyCode
        e.method = p.method.name
        e.status = p.status.name
        e.pgTransactionId = p.pgTransactionId
        e.idempotencyKey = p.idempotencyKey
        e.errorCode = p.errorCode
        e.errorMessage = p.errorMessage
        e.createdAt = p.createdAt
        e.updatedAt = p.updatedAt
        e.version = p.version
        return e
    }

    @JvmStatic
    fun toDomain(e: PaymentJpaEntity): Payment {
        val currency = Currency.getInstance(e.currency)
        return Payment.restore(
            PaymentId(e.id!!),
            OrderId(e.orderId!!),
            Money.of(e.amount, currency),
            PaymentMethod.valueOf(e.method),
            e.idempotencyKey,
            PaymentStatus.valueOf(e.status),
            e.pgTransactionId,
            e.errorCode,
            e.errorMessage,
            e.createdAt,
            e.updatedAt,
            e.version,
        )
    }
}
