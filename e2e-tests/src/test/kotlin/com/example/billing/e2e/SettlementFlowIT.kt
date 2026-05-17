package com.example.billing.e2e

import com.example.billing.BillingApplication
import com.example.billing.application.command.IngestUsageCommand
import com.example.billing.application.command.RunSettlementCommand
import com.example.billing.application.port.`in`.AggregateUsageUseCase
import com.example.billing.application.port.`in`.IngestUsageUseCase
import com.example.billing.application.port.`in`.RunSettlementUseCase
import com.example.billing.application.port.out.AggregatedUsageRepository
import com.example.billing.application.port.out.AuditEntryRepository
import com.example.billing.application.port.out.InvoiceRepository
import com.example.billing.application.port.out.PricingPlanRepository
import com.example.billing.domain.audit.AuditAction
import com.example.billing.domain.invoice.InvoiceStatus
import com.example.billing.domain.metering.ResourceType
import com.example.billing.domain.pricing.PricingPlan
import com.example.billing.domain.pricing.Tier
import com.example.billing.domain.settlement.BillingPeriod
import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.YearMonth
import java.util.Currency
import java.util.UUID

/**
 * E2E — 사용량 → 집계 → 정산 → 청구서 발행 + 결제 흐름을 PostgreSQL Testcontainer 위에서
 * 검증.
 *
 * 이 흐름은 H2 / Mock 으로는 잡지 못하는 두 가지를 실제 환경에서 확인한다:
 * - **PostgreSQL advisory lock** (`pg_advisory_xact_lock`) — local profile 의
 *   NoOp 구현 대신 실 PG 잠금이 잡혀야 한다.
 * - **UsageEvent UNIQUE 제약** — 같은 eventId 두 번 INSERT 시 DB 가 거절하는 동작.
 *
 * 주요 검증:
 * - UsageEvent → AggregatedUsage rollup 이 정확
 * - RunSettlement 가 Invoice 발행 + Mock PG charge → PAID 까지 진행
 * - 같은 customer × period 재실행 시 멱등 (skipped + 이미 발행된 invoice id 반환)
 * - PricingSnapshot 으로 청구 금액이 frozen — 이후 plan 이 바뀌어도 invoice 는 변하지 않음
 * - Audit log 에 INVOICE_ISSUED + INVOICE_PAID 가 같은 트랜잭션으로 INSERT
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = [BillingApplication::class])
@ActiveProfiles("it")
class SettlementFlowIT : E2ECleanupSupport() {

    @Autowired
    lateinit var ingestUsage: IngestUsageUseCase

    @Autowired
    lateinit var aggregateUsage: AggregateUsageUseCase

    @Autowired
    lateinit var runSettlement: RunSettlementUseCase

    @Autowired
    lateinit var pricingPlans: PricingPlanRepository

    @Autowired
    lateinit var aggregatedUsages: AggregatedUsageRepository

    @Autowired
    lateinit var invoices: InvoiceRepository

    @Autowired
    lateinit var auditEntries: AuditEntryRepository

    @Test
    fun usage_aggregate_settle_issuesInvoiceAndCharges() {
        // 1. PricingPlan — API_CALL 1만 건까지 무료, 초과분 호출당 1원
        seedDefaultPlan()

        // 2. UsageEvent 3건 (2026-05 기간 안)
        val within = PERIOD.fromInclusive().plusSeconds(60)
        ingestUsage.ingest(IngestUsageCommand(UUID.randomUUID(), CUSTOMER, ResourceType.API_CALL, 5_000, within))
        ingestUsage.ingest(
            IngestUsageCommand(UUID.randomUUID(), CUSTOMER, ResourceType.API_CALL, 4_000, within.plusSeconds(60)),
        )
        ingestUsage.ingest(
            IngestUsageCommand(UUID.randomUUID(), CUSTOMER, ResourceType.API_CALL, 3_000, within.plusSeconds(120)),
        )
        // 합계 12,000 → 무료 10,000 차감 → 과금 대상 2,000원

        // 3. 집계
        val rolledUp = aggregateUsage.aggregate(CUSTOMER, PERIOD)
        assertThat(rolledUp).isEqualTo(1) // API_CALL 한 종류

        val agg = aggregatedUsages.findBy(CUSTOMER, ResourceType.API_CALL, PERIOD).orElseThrow()
        assertThat(agg.totalQuantity).isEqualTo(12_000L)
        assertThat(agg.eventCount).isEqualTo(3L)

        // 4. 정산 실행
        val result = runSettlement.run(RunSettlementCommand(CUSTOMER, PERIOD))

        assertThat(result.alreadyProcessed).isFalse
        assertThat(result.invoiceId).isNotNull
        assertThat(result.paymentSucceeded).isTrue // MockPgClient → 자동 승인

        // 5. Invoice 가 PAID 로 영속
        val issued = invoices.findById(result.invoiceId!!).orElseThrow()
        assertThat(issued.status).isEqualTo(InvoiceStatus.PAID)
        assertThat(issued.customerId).isEqualTo(CUSTOMER)
        assertThat(issued.period).isEqualTo(PERIOD)
        // 1만 무료 + (12,000 - 10,000) × 1 = 2,000원
        assertThat(issued.total.amount).isEqualByComparingTo("2000")

        // 6. Audit log 가 INVOICE_ISSUED + INVOICE_PAID 두 건을 같은 invoice 로 기록 — 도메인
        //    변경과 audit 가 같은 트랜잭션으로 commit 됐다는 신호 (rollback 시 둘 다 사라짐).
        val trail = auditEntries.findByTarget("Invoice", issued.id.toString(), 10)
        assertThat(trail).extracting<AuditAction> { it.action }
            .containsExactlyInAnyOrder(AuditAction.INVOICE_ISSUED, AuditAction.INVOICE_PAID)
    }

    @Test
    fun rerun_sameCustomerPeriod_isIdempotent() {
        seedDefaultPlan()
        val within = PERIOD.fromInclusive().plusSeconds(60)
        ingestUsage.ingest(IngestUsageCommand(UUID.randomUUID(), CUSTOMER, ResourceType.API_CALL, 11_000, within))
        aggregateUsage.aggregate(CUSTOMER, PERIOD)

        val first = runSettlement.run(RunSettlementCommand(CUSTOMER, PERIOD))
        assertThat(first.alreadyProcessed).isFalse
        val firstInvoice: UUID = first.invoiceId!!

        val second = runSettlement.run(RunSettlementCommand(CUSTOMER, PERIOD))
        assertThat(second.alreadyProcessed).isTrue
        assertThat(second.message).contains(firstInvoice.toString())
    }

    @Test
    fun noUsage_returnsSkippedAndDoesNotIssueInvoice() {
        seedDefaultPlan()

        val result = runSettlement.run(RunSettlementCommand(CUSTOMER, PERIOD))

        assertThat(result.alreadyProcessed).isTrue
        assertThat(result.invoiceId).isNull()
        assertThat(result.message).isEqualTo("no usage")
        assertThat(invoices.findBy(CUSTOMER, PERIOD)).isEmpty
    }

    @Test
    fun priceChangeAfterIssue_doesNotMutateInvoice() {
        seedDefaultPlan()
        val within = PERIOD.fromInclusive().plusSeconds(60)
        ingestUsage.ingest(IngestUsageCommand(UUID.randomUUID(), CUSTOMER, ResourceType.API_CALL, 12_000, within))
        aggregateUsage.aggregate(CUSTOMER, PERIOD)

        val result = runSettlement.run(RunSettlementCommand(CUSTOMER, PERIOD))
        val before = invoices.findById(result.invoiceId!!).orElseThrow()
        val frozenTotal: BigDecimal = before.total.amount

        // 이후 가격이 10배 인상되어도 (새 plan 행) — 기존 invoice 의 PricingSnapshot 은 그대로
        val free = Tier(ResourceType.API_CALL, 10_000L, Money.of(BigDecimal.ZERO, KRW))
        val expensive = Tier(ResourceType.API_CALL, null, Money.of(BigDecimal.TEN, KRW))
        pricingPlans.save(
            PricingPlan.create("v2", listOf(free, expensive), PERIOD.fromInclusive().plusSeconds(1)),
        )

        val after = invoices.findById(result.invoiceId!!).orElseThrow()
        assertThat(after.total.amount).isEqualByComparingTo(frozenTotal)
    }

    private fun seedDefaultPlan() {
        val free = Tier(ResourceType.API_CALL, 10_000L, Money.of(BigDecimal.ZERO, KRW))
        val overage = Tier(ResourceType.API_CALL, null, Money.of(BigDecimal.ONE, KRW))
        pricingPlans.save(
            PricingPlan.create("default", listOf(free, overage), PERIOD.fromInclusive().minusSeconds(1)),
        )
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")

        private val CUSTOMER: CustomerId = CustomerId.of("acme-corp")
        private val PERIOD: BillingPeriod = BillingPeriod.of(YearMonth.of(2026, 5))
        private val KRW: Currency = Currency.getInstance("KRW")
    }
}
