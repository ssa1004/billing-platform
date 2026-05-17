package com.example.billing.adapter.out.persistence.jpa

import com.example.billing.adapter.out.persistence.jpa.entity.PricingPlanJpaEntity
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataPricingPlanRepository
import com.example.billing.application.port.out.PricingPlanRepository
import com.example.billing.domain.metering.ResourceType
import com.example.billing.domain.pricing.PricingPlan
import com.example.billing.domain.pricing.Tier
import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.shared.Money
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.Optional

@Repository
class JpaPricingPlanRepositoryAdapter(
    private val jpa: SpringDataPricingPlanRepository,
) : PricingPlanRepository {

    override fun save(plan: PricingPlan) {
        val entity = jpa.findById(plan.id).orElseGet { PricingPlanJpaEntity() }
        if (entity.id == null) entity.id = plan.id
        entity.name = plan.name
        entity.effectiveFrom = plan.effectiveFrom
        entity.tiersJson = serialize(plan.tiers)
        jpa.save(entity)
    }

    override fun findEffective(customerId: CustomerId, at: Instant): Optional<PricingPlan> {
        val first = jpa.findEffectiveCandidates(customerId.value, at).firstOrNull()
        return Optional.ofNullable(first).map(::toDomain)
    }

    private fun toDomain(e: PricingPlanJpaEntity): PricingPlan =
        PricingPlan.restore(e.id!!, e.name, deserialize(e.tiersJson), e.effectiveFrom)

    private fun serialize(tiers: List<Tier>): String {
        try {
            val dtos = tiers.map(TierDto::from)
            return JSON.writeValueAsString(dtos)
        } catch (e: JsonProcessingException) {
            throw IllegalStateException(e)
        }
    }

    private fun deserialize(json: String): List<Tier> {
        try {
            val dtos: List<TierDto> = JSON.readValue(json, object : TypeReference<List<TierDto>>() {})
            return dtos.map(TierDto::toDomain)
        } catch (e: JsonProcessingException) {
            throw IllegalStateException(e)
        }
    }

    @JvmRecord
    data class TierDto(
        val resourceType: ResourceType,
        val upTo: Long?,
        val amount: BigDecimal,
        val currency: String,
    ) {
        fun toDomain(): Tier = Tier(resourceType, upTo, Money.of(amount, Currency.getInstance(currency)))

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
        private val JSON: ObjectMapper = ObjectMapper().registerModule(JavaTimeModule())
    }
}
