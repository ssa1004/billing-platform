package com.example.billing.adapter.out.persistence.jpa

import com.example.billing.adapter.out.persistence.jpa.mapper.PaymentJpaMapper
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataPaymentRepository
import com.example.billing.application.port.out.PaymentRepository
import com.example.billing.domain.payment.Payment
import com.example.billing.domain.payment.PaymentId
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.util.Optional

@Repository
class JpaPaymentRepositoryAdapter(
    private val jpa: SpringDataPaymentRepository,
    private val clock: Clock,
) : PaymentRepository {

    override fun save(payment: Payment) {
        jpa.save(PaymentJpaMapper.toEntity(payment))
    }

    override fun findById(id: PaymentId): Optional<Payment> =
        jpa.findById(id.value).map(PaymentJpaMapper::toDomain)

    override fun findByIdempotencyKey(key: String): Optional<Payment> =
        jpa.findByIdempotencyKey(key).map(PaymentJpaMapper::toDomain)

    override fun findStalePending(staleBefore: Instant, limit: Int): List<Payment> =
        jpa.findStalePending(staleBefore, PageRequest.of(0, limit))
            .map(PaymentJpaMapper::toDomain)

    override fun softDelete(id: PaymentId, deletedBy: String): Boolean {
        return jpa.softDelete(id.value, deletedBy, clock.instant()) > 0
    }

    override fun findByIdIncludingDeleted(id: PaymentId): Optional<Payment> =
        jpa.findByIdIncludingDeleted(id.value).map(PaymentJpaMapper::toDomain)
}
