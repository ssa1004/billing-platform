package com.example.billing.adapter.out.persistence.jpa.mapper

import com.example.billing.adapter.out.persistence.jpa.entity.RefundJpaEntity
import com.example.billing.domain.payment.PaymentId
import com.example.billing.domain.refund.Refund
import com.example.billing.domain.refund.RefundId
import com.example.billing.domain.refund.RefundStatus
import com.example.billing.domain.shared.Money
import java.util.Currency

object RefundJpaMapper {

    @JvmStatic
    fun toEntity(r: Refund): RefundJpaEntity {
        val e = RefundJpaEntity()
        e.id = r.id.value
        e.paymentId = r.paymentId.value
        e.amount = r.amount.amount
        e.currency = r.amount.currency.currencyCode
        e.reason = r.reason
        e.idempotencyKey = r.idempotencyKey
        e.status = r.status.name
        e.pgRefundId = r.pgRefundId
        e.requestedAt = r.requestedAt
        e.completedAt = r.completedAt
        e.version = r.version
        return e
    }

    @JvmStatic
    fun toDomain(e: RefundJpaEntity): Refund {
        val currency = Currency.getInstance(e.currency)
        return Refund.restore(
            RefundId(e.id!!),
            PaymentId(e.paymentId!!),
            Money.of(e.amount, currency),
            e.reason,
            e.idempotencyKey,
            RefundStatus.valueOf(e.status),
            e.pgRefundId,
            e.requestedAt,
            e.completedAt,
            e.version,
        )
    }
}
