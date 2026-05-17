package com.example.billing.adapter.out.persistence.jpa

import com.example.billing.adapter.out.persistence.jpa.entity.InvoiceJpaEntity
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataInvoiceRepository
import com.example.billing.application.port.out.InvoiceRepository
import com.example.billing.domain.invoice.Invoice
import com.example.billing.domain.invoice.InvoiceLine
import com.example.billing.domain.invoice.InvoiceStatus
import com.example.billing.domain.metering.ResourceType
import com.example.billing.domain.pricing.PricingSnapshot
import com.example.billing.domain.pricing.Tier
import com.example.billing.domain.settlement.BillingPeriod
import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.shared.Money
import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.util.Currency
import java.util.Optional
import java.util.UUID

@Repository
class JpaInvoiceRepositoryAdapter(
    private val jpa: SpringDataInvoiceRepository,
    private val clock: Clock,
) : InvoiceRepository {

    override fun save(invoice: Invoice) {
        val entity = jpa.findById(invoice.id).orElseGet { InvoiceJpaEntity() }
        if (entity.id == null) entity.id = invoice.id
        entity.customerId = invoice.customerId.value
        entity.periodYearMonth = invoice.period.toKey()
        entity.totalAmount = invoice.total.amount
        entity.appliedCredit = invoice.appliedCredit.amount
        entity.currencyCode = invoice.total.currency.currencyCode
        entity.status = invoice.status
        entity.linesJson = serializeLines(invoice.lines)
        entity.pricingSnapshotJson = serializeSnapshot(invoice.pricingSnapshot)
        entity.createdAt = invoice.createdAt
        entity.issuedAt = invoice.issuedAt
        entity.dueAt = invoice.dueAt
        entity.paidAt = invoice.paidAt
        jpa.save(entity)
    }

    override fun findById(id: UUID): Optional<Invoice> = jpa.findById(id).map(::toDomain)

    override fun findBy(customerId: CustomerId, period: BillingPeriod): Optional<Invoice> =
        jpa.findByCustomerIdAndPeriodYearMonth(customerId.value, period.toKey()).map(::toDomain)

    override fun findByCustomer(customerId: CustomerId, limit: Int): List<Invoice> =
        jpa.findByCustomerIdOrderByPeriodYearMonthDesc(customerId.value, PageRequest.of(0, limit))
            .map(::toDomain)

    override fun findIssuedForRetryForUpdateSkipLocked(status: InvoiceStatus, limit: Int): List<Invoice> =
        jpa.findForRetryWithLock(status, PageRequest.of(0, limit)).map(::toDomain)

    override fun findUnpaid(asOf: Instant, limit: Int): List<Invoice> =
        jpa.findUnpaidAsOf(
            listOf(InvoiceStatus.ISSUED, InvoiceStatus.OVERDUE),
            PageRequest.of(0, limit),
        ).map(::toDomain)

    override fun softDelete(id: UUID, deletedBy: String): Boolean {
        return jpa.softDelete(id, deletedBy, clock.instant()) > 0
    }

    override fun findByIdIncludingDeleted(id: UUID): Optional<Invoice> =
        jpa.findByIdIncludingDeleted(id).map(::toDomain)

    private fun toDomain(e: InvoiceJpaEntity): Invoice {
        val currency = Currency.getInstance(e.currencyCode)
        val total = Money.of(e.totalAmount, currency)
        val appliedCredit = Money.of(e.appliedCredit, currency)
        val lines = deserializeLines(e.linesJson)
        val snapshot = deserializeSnapshot(e.pricingSnapshotJson)
        return Invoice.restore(
            e.id!!,
            CustomerId.of(e.customerId),
            BillingPeriod.of(YearMonth.parse(e.periodYearMonth)),
            lines,
            total,
            snapshot,
            e.createdAt,
            e.status,
            appliedCredit,
            e.issuedAt,
            e.dueAt,
            e.paidAt,
            e.version,
        )
    }

    // ── JSON 직렬화 ──

    private fun serializeLines(lines: List<InvoiceLine>): String {
        try {
            val dtos = lines.map(LineDto::from)
            return JSON.writeValueAsString(dtos)
        } catch (ex: JsonProcessingException) {
            throw IllegalStateException("failed to serialize invoice lines", ex)
        }
    }

    private fun deserializeLines(json: String): List<InvoiceLine> {
        try {
            val dtos: List<LineDto> = JSON.readValue(json, object : TypeReference<List<LineDto>>() {})
            return dtos.map(LineDto::toDomain)
        } catch (ex: JsonProcessingException) {
            throw IllegalStateException("failed to deserialize invoice lines", ex)
        }
    }

    private fun serializeSnapshot(snapshot: PricingSnapshot): String {
        try {
            return JSON.writeValueAsString(SnapshotDto.from(snapshot))
        } catch (ex: JsonProcessingException) {
            throw IllegalStateException("failed to serialize pricing snapshot", ex)
        }
    }

    private fun deserializeSnapshot(json: String): PricingSnapshot {
        try {
            val dto = JSON.readValue(json, SnapshotDto::class.java)
            return dto.toDomain()
        } catch (ex: JsonProcessingException) {
            throw IllegalStateException("failed to deserialize pricing snapshot", ex)
        }
    }

    // ── DTO records (JSON friendly) ──

    @JvmRecord
    data class LineDto(
        val resourceType: ResourceType,
        val quantity: Long,
        val lineAmount: BigDecimal,
        val currency: String,
        val unitPriceDescription: String,
    ) {
        fun toDomain(): InvoiceLine = InvoiceLine(
            resourceType,
            quantity,
            Money.of(lineAmount, Currency.getInstance(currency)),
            unitPriceDescription,
        )

        companion object {
            @JvmStatic
            fun from(line: InvoiceLine): LineDto = LineDto(
                line.resourceType,
                line.quantity,
                line.lineTotal.amount,
                line.lineTotal.currency.currencyCode,
                line.unitPriceDescription,
            )
        }
    }

    @JvmRecord
    data class SnapshotDto(
        val planId: UUID,
        val planName: String,
        val tiers: List<TierDto>,
        val capturedAt: Instant,
    ) {
        fun toDomain(): PricingSnapshot {
            val domainTiers = tiers.map(TierDto::toDomain)
            return PricingSnapshot.of(planId, planName, domainTiers, capturedAt)
        }

        companion object {
            @JvmStatic
            fun from(s: PricingSnapshot): SnapshotDto = SnapshotDto(
                s.planId,
                s.planName,
                s.tiers.map(TierDto::from),
                s.capturedAt,
            )
        }
    }

    @JvmRecord
    data class TierDto(
        val resourceType: ResourceType,
        val upTo: Long?,
        val unitPriceAmount: BigDecimal,
        val currency: String,
    ) {
        fun toDomain(): Tier = Tier(
            resourceType,
            upTo,
            Money.of(unitPriceAmount, Currency.getInstance(currency)),
        )

        companion object {
            @JvmStatic
            fun from(t: Tier): TierDto = TierDto(
                t.resourceType,
                t.upTo,
                t.unitPrice.amount,
                t.unitPrice.currency.currencyCode,
            )
        }
    }

    companion object {
        private val JSON: ObjectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
    }
}
