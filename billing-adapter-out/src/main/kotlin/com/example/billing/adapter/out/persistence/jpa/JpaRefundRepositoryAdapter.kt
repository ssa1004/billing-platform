package com.example.billing.adapter.out.persistence.jpa

import com.example.billing.adapter.out.persistence.jpa.mapper.RefundJpaMapper
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataRefundRepository
import com.example.billing.application.port.out.RefundRepository
import com.example.billing.domain.payment.PaymentId
import com.example.billing.domain.refund.Refund
import com.example.billing.domain.refund.RefundId
import com.example.billing.domain.refund.RefundStatus
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.util.Optional

@Repository
class JpaRefundRepositoryAdapter(
    private val jpa: SpringDataRefundRepository,
    private val clock: Clock,
) : RefundRepository {

    override fun save(refund: Refund) {
        jpa.save(RefundJpaMapper.toEntity(refund))
    }

    override fun findById(id: RefundId): Optional<Refund> =
        jpa.findById(id.value).map(RefundJpaMapper::toDomain)

    override fun existsActiveByPaymentId(paymentId: PaymentId): Boolean =
        jpa.existsByPaymentIdAndStatusNot(paymentId.value, RefundStatus.FAILED.name)

    override fun findStaleRequested(staleBefore: Instant, limit: Int): List<Refund> =
        jpa.findStaleRequested(staleBefore, PageRequest.of(0, limit))
            .map(RefundJpaMapper::toDomain)

    override fun softDelete(id: RefundId, deletedBy: String): Boolean {
        return jpa.softDelete(id.value, deletedBy, clock.instant()) > 0
    }

    override fun findByIdIncludingDeleted(id: RefundId): Optional<Refund> =
        jpa.findByIdIncludingDeleted(id.value).map(RefundJpaMapper::toDomain)
}
