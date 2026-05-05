package com.example.billing.adapter.out.persistence.jpa;

import com.example.billing.adapter.out.persistence.jpa.entity.PricingPlanJpaEntity;
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataPricingPlanRepository;
import com.example.billing.application.port.out.PricingPlanRepository;
import com.example.billing.domain.metering.ResourceType;
import com.example.billing.domain.pricing.PricingPlan;
import com.example.billing.domain.pricing.Tier;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

@Repository
public class JpaPricingPlanRepositoryAdapter implements PricingPlanRepository {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private final SpringDataPricingPlanRepository jpa;

    public JpaPricingPlanRepositoryAdapter(SpringDataPricingPlanRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(PricingPlan plan) {
        PricingPlanJpaEntity entity = jpa.findById(plan.id()).orElseGet(PricingPlanJpaEntity::new);
        if (entity.getId() == null) entity.setId(plan.id());
        entity.setName(plan.name());
        entity.setEffectiveFrom(plan.effectiveFrom());
        entity.setTiersJson(serialize(plan.tiers()));
        jpa.save(entity);
    }

    @Override
    public Optional<PricingPlan> findEffective(CustomerId customerId, Instant at) {
        return jpa.findEffective(customerId.value(), at).map(this::toDomain);
    }

    private PricingPlan toDomain(PricingPlanJpaEntity e) {
        return PricingPlan.restore(e.getId(), e.getName(), deserialize(e.getTiersJson()),
                e.getEffectiveFrom());
    }

    private String serialize(List<Tier> tiers) {
        try {
            List<TierDto> dtos = tiers.stream().map(TierDto::from).toList();
            return JSON.writeValueAsString(dtos);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Tier> deserialize(String json) {
        try {
            List<TierDto> dtos = JSON.readValue(json, new TypeReference<List<TierDto>>() {});
            return dtos.stream().map(TierDto::toDomain).toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    record TierDto(ResourceType resourceType, Long upTo, BigDecimal amount, String currency) {
        static TierDto from(Tier t) {
            return new TierDto(t.resourceType(), t.upTo(),
                    t.unitPrice().amount(), t.unitPrice().currency().getCurrencyCode());
        }
        Tier toDomain() {
            return new Tier(resourceType, upTo,
                    Money.of(amount, Currency.getInstance(currency)));
        }
    }
}
