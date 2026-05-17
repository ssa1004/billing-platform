package com.example.billing.adapter.out.persistence.jpa

import com.example.billing.adapter.out.persistence.jpa.mapper.CreditJpaMapper
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataCreditRepository
import com.example.billing.application.port.out.CreditRepository
import com.example.billing.domain.credit.Credit
import com.example.billing.domain.credit.CreditId
import com.example.billing.domain.shared.CustomerId
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.Optional

@Repository
class JpaCreditRepositoryAdapter(
    private val jpa: SpringDataCreditRepository,
) : CreditRepository {

    override fun save(credit: Credit) {
        jpa.save(CreditJpaMapper.toEntity(credit))
    }

    override fun findById(id: CreditId): Optional<Credit> =
        jpa.findById(id.value).map(CreditJpaMapper::toDomain)

    override fun findUsable(customerId: CustomerId, now: Instant): List<Credit> =
        jpa.findUsable(customerId.value, now).map(CreditJpaMapper::toDomain)

    override fun findExpiredCandidates(now: Instant, limit: Int): List<Credit> =
        jpa.findExpiredCandidates(now, PageRequest.of(0, limit)).map(CreditJpaMapper::toDomain)

    override fun findExpiringSoon(customerId: CustomerId, now: Instant, until: Instant): List<Credit> =
        jpa.findExpiringSoon(customerId.value, now, until).map(CreditJpaMapper::toDomain)

    override fun findAllByCustomer(customerId: CustomerId, limit: Int): List<Credit> =
        jpa.findByCustomerIdOrderByCreatedAtDesc(customerId.value, PageRequest.of(0, limit))
            .map(CreditJpaMapper::toDomain)
}
