package com.example.billing.application.service;

import com.example.billing.application.command.RunSettlementCommand;
import com.example.billing.application.command.SettlementResult;
import com.example.billing.application.port.in.RunSettlementUseCase;
import com.example.billing.application.port.out.AdvisoryLock;
import com.example.billing.application.port.out.AggregatedUsageRepository;
import com.example.billing.application.port.out.InvoiceRepository;
import com.example.billing.application.port.out.PaymentClient;
import com.example.billing.application.port.out.PricingPlanRepository;
import com.example.billing.domain.invoice.Invoice;
import com.example.billing.domain.invoice.InvoiceLine;
import com.example.billing.domain.metering.AggregatedUsage;
import com.example.billing.domain.pricing.PricingPlan;
import com.example.billing.domain.pricing.PricingSnapshot;
import com.example.billing.domain.shared.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * 한 customer × 한 BillingPeriod 의 정산.
 *
 * <p>핵심 설계:
 * <ul>
 *   <li><b>advisory lock</b> — {@code settlement:<customerId>:<period>} 키로 같은 정산이 두
 *       worker 에서 동시에 시작되지 않도록 직렬화. 트랜잭션 종료 시 자동 해제.</li>
 *   <li><b>idempotency</b> — 같은 customer × period 에 이미 invoice 가 있으면 skip (재실행
 *       안전).</li>
 *   <li><b>frozen pricing</b> — 청구서 생성 시점의 PricingSnapshot 을 invoice 에 저장. plan
 *       변경에도 과거 청구서 금액 변하지 않음.</li>
 *   <li><b>partial failure</b> — invoice 발행은 성공했지만 결제 실패한 경우, invoice 는
 *       ISSUED 로 남아 있고 별도 retry job 이 처리.</li>
 * </ul>
 */
@Service
public class RunSettlementService implements RunSettlementUseCase {

    private static final Logger log = LoggerFactory.getLogger(RunSettlementService.class);

    private final AdvisoryLock advisoryLock;
    private final AggregatedUsageRepository usageRepository;
    private final PricingPlanRepository pricingPlanRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentClient paymentClient;
    private final Clock clock;

    public RunSettlementService(AdvisoryLock advisoryLock,
                                AggregatedUsageRepository usageRepository,
                                PricingPlanRepository pricingPlanRepository,
                                InvoiceRepository invoiceRepository,
                                PaymentClient paymentClient,
                                Clock clock) {
        this.advisoryLock = advisoryLock;
        this.usageRepository = usageRepository;
        this.pricingPlanRepository = pricingPlanRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentClient = paymentClient;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SettlementResult run(RunSettlementCommand cmd) {
        // 1. 동시 실행 차단 — 같은 customer × period 정산은 직렬화
        String lockKey = "settlement:" + cmd.customerId().value() + ":" + cmd.period().toKey();
        advisoryLock.lock(lockKey);

        // 2. 이미 발행된 invoice 가 있으면 skip (idempotency)
        var existing = invoiceRepository.findBy(cmd.customerId(), cmd.period());
        if (existing.isPresent()) {
            log.info("invoice already exists for customer={} period={}, skipping",
                    cmd.customerId(), cmd.period());
            return SettlementResult.skipped(cmd.customerId(), cmd.period(),
                    "invoice already issued: " + existing.get().id());
        }

        // 3. 월 사용량 집계 조회
        List<AggregatedUsage> usages = usageRepository.findByCustomerAndPeriod(
                cmd.customerId(), cmd.period());
        if (usages.isEmpty()) {
            log.info("no usage for customer={} period={}, skipping",
                    cmd.customerId(), cmd.period());
            return SettlementResult.skipped(cmd.customerId(), cmd.period(), "no usage");
        }

        // 4. 적용 가능한 가격 정책 + 스냅샷
        PricingPlan plan = pricingPlanRepository.findEffective(cmd.customerId(),
                        cmd.period().toExclusive())
                .orElseThrow(() -> new IllegalStateException(
                        "no pricing plan for customer " + cmd.customerId()));
        PricingSnapshot snapshot = plan.snapshot(clock.instant());

        // 5. 청구서 생성
        List<InvoiceLine> lines = usages.stream()
                .map(u -> {
                    Money amount = snapshot.calculate(u.resourceType(), u.totalQuantity());
                    String desc = describe(u);
                    return new InvoiceLine(u.resourceType(), u.totalQuantity(), amount, desc);
                })
                .toList();
        Invoice invoice = Invoice.draft(cmd.customerId(), cmd.period(), lines, snapshot, clock);
        invoice.issue(clock);
        invoiceRepository.save(invoice);

        // 6. 결제 시도 (실패해도 invoice 는 ISSUED 로 남음 → retry job 이 잡음)
        boolean paid;
        try {
            paid = paymentClient.charge(invoice);
            if (paid) {
                invoice.markPaid(clock);
                invoiceRepository.save(invoice);
            }
        } catch (PaymentClient.PaymentFatalException e) {
            log.warn("payment fatal for invoice={}: {}", invoice.id(), e.getMessage());
            paid = false;
        } catch (RuntimeException e) {
            log.warn("payment transient failure for invoice={}: {}", invoice.id(), e.getMessage());
            paid = false;
        }

        return SettlementResult.success(cmd.customerId(), cmd.period(), invoice.id(), paid);
    }

    private String describe(AggregatedUsage usage) {
        return usage.resourceType() + " × " + usage.totalQuantity()
                + " (" + usage.eventCount() + " events)";
    }
}
