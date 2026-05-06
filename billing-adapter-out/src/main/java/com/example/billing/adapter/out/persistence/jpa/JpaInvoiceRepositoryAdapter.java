package com.example.billing.adapter.out.persistence.jpa;

import com.example.billing.adapter.out.persistence.jpa.entity.InvoiceJpaEntity;
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataInvoiceRepository;
import com.example.billing.application.port.out.InvoiceRepository;
import com.example.billing.domain.invoice.Invoice;
import com.example.billing.domain.invoice.InvoiceLine;
import com.example.billing.domain.invoice.InvoiceStatus;
import com.example.billing.domain.metering.ResourceType;
import com.example.billing.domain.pricing.PricingSnapshot;
import com.example.billing.domain.pricing.Tier;
import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaInvoiceRepositoryAdapter implements InvoiceRepository {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

    private final SpringDataInvoiceRepository jpa;

    public JpaInvoiceRepositoryAdapter(SpringDataInvoiceRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(Invoice invoice) {
        InvoiceJpaEntity entity = jpa.findById(invoice.id()).orElseGet(InvoiceJpaEntity::new);
        if (entity.getId() == null) entity.setId(invoice.id());
        entity.setCustomerId(invoice.customerId().value());
        entity.setPeriodYearMonth(invoice.period().toKey());
        entity.setTotalAmount(invoice.total().amount());
        entity.setAppliedCredit(invoice.appliedCredit().amount());
        entity.setCurrencyCode(invoice.total().currency().getCurrencyCode());
        entity.setStatus(invoice.status());
        entity.setLinesJson(serializeLines(invoice.lines()));
        entity.setPricingSnapshotJson(serializeSnapshot(invoice.pricingSnapshot()));
        entity.setCreatedAt(invoice.createdAt());
        entity.setIssuedAt(invoice.issuedAt());
        entity.setDueAt(invoice.dueAt());
        entity.setPaidAt(invoice.paidAt());
        jpa.save(entity);
    }

    @Override
    public Optional<Invoice> findById(UUID id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<Invoice> findBy(CustomerId customerId, BillingPeriod period) {
        return jpa.findByCustomerIdAndPeriodYearMonth(customerId.value(), period.toKey())
                .map(this::toDomain);
    }

    @Override
    public List<Invoice> findByCustomer(CustomerId customerId, int limit) {
        return jpa.findByCustomerIdOrderByPeriodYearMonthDesc(customerId.value(),
                        PageRequest.of(0, limit))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Invoice> findIssuedForRetryForUpdateSkipLocked(InvoiceStatus status, int limit) {
        return jpa.findForRetryWithLock(status, PageRequest.of(0, limit))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Invoice> findUnpaid(java.time.Instant asOf, int limit) {
        return jpa.findUnpaidAsOf(
                List.of(InvoiceStatus.ISSUED, InvoiceStatus.OVERDUE),
                PageRequest.of(0, limit))
                .stream().map(this::toDomain).toList();
    }

    private Invoice toDomain(InvoiceJpaEntity e) {
        Currency currency = Currency.getInstance(e.getCurrencyCode());
        Money total = Money.of(e.getTotalAmount(), currency);
        Money appliedCredit = e.getAppliedCredit() == null
                ? Money.zero(currency)
                : Money.of(e.getAppliedCredit(), currency);
        List<InvoiceLine> lines = deserializeLines(e.getLinesJson());
        PricingSnapshot snapshot = deserializeSnapshot(e.getPricingSnapshotJson());
        return Invoice.restore(
                e.getId(), CustomerId.of(e.getCustomerId()),
                BillingPeriod.of(YearMonth.parse(e.getPeriodYearMonth())),
                lines, total, snapshot, e.getCreatedAt(),
                e.getStatus(), appliedCredit,
                e.getIssuedAt(), e.getDueAt(), e.getPaidAt(),
                e.getVersion());
    }

    // ── JSON 직렬화 ──

    private String serializeLines(List<InvoiceLine> lines) {
        try {
            List<LineDto> dtos = lines.stream().map(LineDto::from).toList();
            return JSON.writeValueAsString(dtos);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize invoice lines", ex);
        }
    }

    private List<InvoiceLine> deserializeLines(String json) {
        try {
            List<LineDto> dtos = JSON.readValue(json, new TypeReference<List<LineDto>>() {});
            return dtos.stream().map(LineDto::toDomain).toList();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to deserialize invoice lines", ex);
        }
    }

    private String serializeSnapshot(PricingSnapshot snapshot) {
        try {
            return JSON.writeValueAsString(SnapshotDto.from(snapshot));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize pricing snapshot", ex);
        }
    }

    private PricingSnapshot deserializeSnapshot(String json) {
        try {
            SnapshotDto dto = JSON.readValue(json, SnapshotDto.class);
            return dto.toDomain();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to deserialize pricing snapshot", ex);
        }
    }

    // ── DTO records (JSON friendly) ──

    record LineDto(ResourceType resourceType, long quantity, BigDecimal lineAmount,
                   String currency, String unitPriceDescription) {
        static LineDto from(InvoiceLine line) {
            return new LineDto(line.resourceType(), line.quantity(),
                    line.lineTotal().amount(),
                    line.lineTotal().currency().getCurrencyCode(),
                    line.unitPriceDescription());
        }
        InvoiceLine toDomain() {
            return new InvoiceLine(resourceType, quantity,
                    Money.of(lineAmount, Currency.getInstance(currency)),
                    unitPriceDescription);
        }
    }

    record SnapshotDto(UUID planId, String planName, List<TierDto> tiers, java.time.Instant capturedAt) {
        static SnapshotDto from(PricingSnapshot s) {
            List<TierDto> tiers = s.tiers().stream().map(TierDto::from).toList();
            return new SnapshotDto(s.planId(), s.planName(), tiers, s.capturedAt());
        }
        PricingSnapshot toDomain() {
            List<Tier> domainTiers = tiers.stream().map(TierDto::toDomain).toList();
            return PricingSnapshot.of(planId, planName, domainTiers, capturedAt);
        }
    }

    record TierDto(ResourceType resourceType, Long upTo, BigDecimal unitPriceAmount, String currency) {
        static TierDto from(Tier t) {
            return new TierDto(t.resourceType(), t.upTo(),
                    t.unitPrice().amount(),
                    t.unitPrice().currency().getCurrencyCode());
        }
        Tier toDomain() {
            return new Tier(resourceType, upTo,
                    Money.of(unitPriceAmount, Currency.getInstance(currency)));
        }
    }
}
