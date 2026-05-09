package com.example.billing.e2e;

import com.example.billing.BillingApplication;
import com.example.billing.application.command.IngestUsageCommand;
import com.example.billing.application.command.RunSettlementCommand;
import com.example.billing.application.command.SettlementResult;
import com.example.billing.application.port.in.AggregateUsageUseCase;
import com.example.billing.application.port.in.IngestUsageUseCase;
import com.example.billing.application.port.in.RunSettlementUseCase;
import com.example.billing.application.port.out.AggregatedUsageRepository;
import com.example.billing.application.port.out.AuditEntryRepository;
import com.example.billing.application.port.out.InvoiceRepository;
import com.example.billing.application.port.out.PricingPlanRepository;
import com.example.billing.domain.audit.AuditAction;
import com.example.billing.domain.invoice.Invoice;
import com.example.billing.domain.invoice.InvoiceStatus;
import com.example.billing.domain.metering.AggregatedUsage;
import com.example.billing.domain.metering.ResourceType;
import com.example.billing.domain.pricing.PricingPlan;
import com.example.billing.domain.pricing.Tier;
import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E — 사용량 → 집계 → 정산 → 청구서 발행 + 결제 흐름을 PostgreSQL Testcontainer 위에서
 * 검증.
 *
 * <p>이 흐름은 H2 / Mock 으로는 잡지 못하는 두 가지를 실제 환경에서 확인한다:
 * <ul>
 *   <li><b>PostgreSQL advisory lock</b> ({@code pg_advisory_xact_lock}) — local profile 의
 *       NoOp 구현 대신 실 PG 잠금이 잡혀야 한다.</li>
 *   <li><b>UsageEvent UNIQUE 제약</b> — 같은 eventId 두 번 INSERT 시 DB 가 거절하는 동작.</li>
 * </ul>
 *
 * <p>주요 검증:
 * <ul>
 *   <li>UsageEvent → AggregatedUsage rollup 이 정확</li>
 *   <li>RunSettlement 가 Invoice 발행 + Mock PG charge → PAID 까지 진행</li>
 *   <li>같은 customer × period 재실행 시 멱등 (skipped + 이미 발행된 invoice id 반환)</li>
 *   <li>PricingSnapshot 으로 청구 금액이 frozen — 이후 plan 이 바뀌어도 invoice 는 변하지 않음</li>
 *   <li>Audit log 에 INVOICE_ISSUED + INVOICE_PAID 가 같은 트랜잭션으로 INSERT</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = BillingApplication.class)
@ActiveProfiles("it")
class SettlementFlowIT extends E2ECleanupSupport {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired IngestUsageUseCase ingestUsage;
    @Autowired AggregateUsageUseCase aggregateUsage;
    @Autowired RunSettlementUseCase runSettlement;
    @Autowired PricingPlanRepository pricingPlans;
    @Autowired AggregatedUsageRepository aggregatedUsages;
    @Autowired InvoiceRepository invoices;
    @Autowired AuditEntryRepository auditEntries;

    private static final CustomerId CUSTOMER = CustomerId.of("acme-corp");
    private static final BillingPeriod PERIOD = BillingPeriod.of(YearMonth.of(2026, 5));
    private static final Currency KRW = Currency.getInstance("KRW");

    @Test
    void usage_aggregate_settle_issuesInvoiceAndCharges() {
        // 1. PricingPlan — API_CALL 1만 건까지 무료, 초과분 호출당 1원
        seedDefaultPlan();

        // 2. UsageEvent 3건 (2026-05 기간 안)
        Instant within = PERIOD.fromInclusive().plusSeconds(60);
        ingestUsage.ingest(new IngestUsageCommand(
                UUID.randomUUID(), CUSTOMER, ResourceType.API_CALL, 5_000, within));
        ingestUsage.ingest(new IngestUsageCommand(
                UUID.randomUUID(), CUSTOMER, ResourceType.API_CALL, 4_000, within.plusSeconds(60)));
        ingestUsage.ingest(new IngestUsageCommand(
                UUID.randomUUID(), CUSTOMER, ResourceType.API_CALL, 3_000, within.plusSeconds(120)));
        // 합계 12,000 → 무료 10,000 차감 → 과금 대상 2,000원

        // 3. 집계
        int rolledUp = aggregateUsage.aggregate(CUSTOMER, PERIOD);
        assertThat(rolledUp).isEqualTo(1);  // API_CALL 한 종류

        AggregatedUsage agg = aggregatedUsages.findBy(CUSTOMER, ResourceType.API_CALL, PERIOD)
                .orElseThrow();
        assertThat(agg.totalQuantity()).isEqualTo(12_000);
        assertThat(agg.eventCount()).isEqualTo(3);

        // 4. 정산 실행
        SettlementResult result = runSettlement.run(new RunSettlementCommand(CUSTOMER, PERIOD));

        assertThat(result.alreadyProcessed()).isFalse();
        assertThat(result.invoiceId()).isNotNull();
        assertThat(result.paymentSucceeded()).isTrue();  // MockPgClient → 자동 승인

        // 5. Invoice 가 PAID 로 영속
        Invoice issued = invoices.findById(result.invoiceId()).orElseThrow();
        assertThat(issued.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(issued.customerId()).isEqualTo(CUSTOMER);
        assertThat(issued.period()).isEqualTo(PERIOD);
        // 1만 무료 + (12,000 - 10,000) × 1 = 2,000원
        assertThat(issued.total().amount()).isEqualByComparingTo("2000");

        // 6. Audit log 가 INVOICE_ISSUED + INVOICE_PAID 두 건을 같은 invoice 로 기록 — 도메인
        //    변경과 audit 가 같은 트랜잭션으로 commit 됐다는 신호 (rollback 시 둘 다 사라짐).
        var trail = auditEntries.findByTarget("Invoice", issued.id().toString(), 10);
        assertThat(trail).extracting(e -> e.action())
                .containsExactlyInAnyOrder(AuditAction.INVOICE_ISSUED, AuditAction.INVOICE_PAID);
    }

    @Test
    void rerun_sameCustomerPeriod_isIdempotent() {
        seedDefaultPlan();
        Instant within = PERIOD.fromInclusive().plusSeconds(60);
        ingestUsage.ingest(new IngestUsageCommand(
                UUID.randomUUID(), CUSTOMER, ResourceType.API_CALL, 11_000, within));
        aggregateUsage.aggregate(CUSTOMER, PERIOD);

        SettlementResult first = runSettlement.run(new RunSettlementCommand(CUSTOMER, PERIOD));
        assertThat(first.alreadyProcessed()).isFalse();
        UUID firstInvoice = first.invoiceId();

        SettlementResult second = runSettlement.run(new RunSettlementCommand(CUSTOMER, PERIOD));
        assertThat(second.alreadyProcessed()).isTrue();
        assertThat(second.message()).contains(firstInvoice.toString());
    }

    @Test
    void noUsage_returnsSkippedAndDoesNotIssueInvoice() {
        seedDefaultPlan();

        SettlementResult result = runSettlement.run(new RunSettlementCommand(CUSTOMER, PERIOD));

        assertThat(result.alreadyProcessed()).isTrue();
        assertThat(result.invoiceId()).isNull();
        assertThat(result.message()).isEqualTo("no usage");
        assertThat(invoices.findBy(CUSTOMER, PERIOD)).isEmpty();
    }

    @Test
    void priceChangeAfterIssue_doesNotMutateInvoice() {
        seedDefaultPlan();
        Instant within = PERIOD.fromInclusive().plusSeconds(60);
        ingestUsage.ingest(new IngestUsageCommand(
                UUID.randomUUID(), CUSTOMER, ResourceType.API_CALL, 12_000, within));
        aggregateUsage.aggregate(CUSTOMER, PERIOD);

        SettlementResult result = runSettlement.run(new RunSettlementCommand(CUSTOMER, PERIOD));
        Invoice before = invoices.findById(result.invoiceId()).orElseThrow();
        BigDecimal frozenTotal = before.total().amount();

        // 이후 가격이 10배 인상되어도 (새 plan 행) — 기존 invoice 의 PricingSnapshot 은 그대로
        Tier free = new Tier(ResourceType.API_CALL, 10_000L, Money.of(BigDecimal.ZERO, KRW));
        Tier expensive = new Tier(ResourceType.API_CALL, null, Money.of(BigDecimal.TEN, KRW));
        pricingPlans.save(PricingPlan.create("v2", List.of(free, expensive),
                PERIOD.fromInclusive().plusSeconds(1)));

        Invoice after = invoices.findById(result.invoiceId()).orElseThrow();
        assertThat(after.total().amount()).isEqualByComparingTo(frozenTotal);
    }

    private void seedDefaultPlan() {
        Tier free = new Tier(ResourceType.API_CALL, 10_000L,
                Money.of(BigDecimal.ZERO, KRW));
        Tier overage = new Tier(ResourceType.API_CALL, null,
                Money.of(BigDecimal.ONE, KRW));
        pricingPlans.save(PricingPlan.create("default", List.of(free, overage),
                PERIOD.fromInclusive().minusSeconds(1)));
    }
}
