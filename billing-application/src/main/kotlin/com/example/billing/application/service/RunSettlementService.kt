package com.example.billing.application.service

import com.example.billing.application.command.RunSettlementCommand
import com.example.billing.application.command.SettlementResult
import com.example.billing.application.port.`in`.AuditLogger
import com.example.billing.application.port.`in`.RunSettlementUseCase
import com.example.billing.application.port.out.AdvisoryLock
import com.example.billing.application.port.out.AggregatedUsageRepository
import com.example.billing.application.port.out.InvoiceRepository
import com.example.billing.application.port.out.PaymentClient
import com.example.billing.application.port.out.PricingPlanRepository
import com.example.billing.domain.audit.AuditAction
import com.example.billing.domain.audit.AuditActor
import com.example.billing.domain.invoice.Invoice
import com.example.billing.domain.invoice.InvoiceLine
import com.example.billing.domain.metering.AggregatedUsage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 한 customer × 한 BillingPeriod (청구 기간) 의 정산.
 *
 * 핵심 설계:
 *  - **advisory lock** (Postgres 의 이름 기반 잠금, 트랜잭션 끝나면 자동 해제) —
 *    `settlement:<customerId>:<period>` 키로 같은 정산이 두 worker 에서 동시에
 *    시작되지 않도록 직렬화. Phase 1 트랜잭션 종료 시 자동 해제되며, 이후엔 발행된
 *    invoice 의 존재가 동시 재실행을 막는다(멱등).
 *  - **멱등성 (idempotency)** — 같은 customer × period 에 이미 invoice 가 있으면 skip.
 *    재실행해도 안전.
 *  - **frozen pricing (요금표 동결)** — 청구서 생성 시점의 PricingSnapshot (그 시점
 *    요금표를 그대로 보관한 값 객체) 을 invoice 에 저장. 요금제가 바뀌어도 과거 청구서
 *    금액은 변하지 않음.
 *
 * **왜 3-phase 인가 (외부 PG 호출을 트랜잭션 밖으로 빼는 이유)**: 예전 구현은 invoice 발행과
 * 외부 결제(charge) 를 한 트랜잭션에 묶어, (a) PG 응답 동안 DB connection 과 advisory lock 을
 * 계속 점유해 다른 도메인까지 멈추는 cascade 위험, (b) charge 성공 후 커밋이 깨지면 invoice
 * 자체가 롤백되어 다음 실행이 새 id 로 재발행·재청구하는 이중청구 위험이 있었다. 그래서
 * `ProcessPaymentService` 와 같은 3-phase 로 나눈다:
 *  1. **Phase 1 (DB tx, 짧음)**: advisory lock + 멱등 확인 + invoice 발행(ISSUED) → commit.
 *     외부 호출이 없으니 connection 을 길게 잡지 않고, invoice.id 가 charge 이전에 확정된다.
 *  2. **Phase 2 (트랜잭션 밖)**: `paymentClient.charge(invoice)`. connection / advisory lock 을
 *     잡지 않은 채 PG 응답을 대기. 실제 PG 어댑터는 `invoice.id` 를 PG 멱등키로 사용해 재청구를
 *     dedup 해야 한다(발행이 이미 커밋돼 있어 id 가 안정적).
 *  3. **Phase 3 (DB tx, 짧음)**: 결제 성공 시 markPaid → commit.
 *
 * **부분 실패**: Phase 2/3 이 실패해도 invoice 는 ISSUED 로 남는다. 이 경우 "PG 는 과금됐는데
 * 우리 쪽은 미결제" 상태가 될 수 있어, ISSUED 미결제 invoice 를 다시 조회해 결제 결과를
 * 동기화하는 재청구/reconcile 이 별도로 필요하다.
 */
@Service
open class RunSettlementService(
    private val advisoryLock: AdvisoryLock,
    private val usageRepository: AggregatedUsageRepository,
    private val pricingPlanRepository: PricingPlanRepository,
    private val invoiceRepository: InvoiceRepository,
    private val paymentClient: PaymentClient,
    private val audit: AuditLogger,
    private val clock: java.time.Clock,
    txManager: PlatformTransactionManager,
) : RunSettlementUseCase {

    private val tx = TransactionTemplate(txManager)

    override fun run(cmd: RunSettlementCommand): SettlementResult {
        // Phase 1 — invoice 발행 (짧은 tx, 외부 호출 없음)
        val issued = when (val outcome = tx.execute { initiate(cmd) }!!) {
            is InitiateOutcome.Skipped -> return outcome.result
            is InitiateOutcome.Issued -> outcome.invoice
        }

        // Phase 2 — 결제 시도 (트랜잭션 밖: PG 응답 동안 connection / advisory lock 을 잡지 않는다)
        val paid = attemptCharge(issued)

        // Phase 3 — 승인 시 markPaid (짧은 tx). 실패 시 invoice 는 ISSUED 로 남아 재청구 대상이 된다.
        if (paid) {
            tx.executeWithoutResult { markInvoicePaid(cmd, issued) }
        }
        return SettlementResult.success(cmd.customerId, cmd.period, issued.id, paid)
    }

    private fun initiate(cmd: RunSettlementCommand): InitiateOutcome {
        // 1. 동시 실행 차단 — 같은 customer × period 정산은 한 번에 하나씩만 진행
        val lockKey = "settlement:${cmd.customerId.value}:${cmd.period.toKey()}"
        advisoryLock.lock(lockKey)

        // 2. 이미 발행된 invoice 가 있으면 skip (멱등성: 재실행해도 안전)
        val existing = invoiceRepository.findBy(cmd.customerId, cmd.period)
        if (existing.isPresent) {
            log.info(
                "invoice already exists for customer={} period={}, skipping",
                cmd.customerId, cmd.period,
            )
            return InitiateOutcome.Skipped(
                SettlementResult.skipped(
                    cmd.customerId, cmd.period,
                    "invoice already issued: ${existing.get().id}",
                ),
            )
        }

        // 3. 월 사용량 집계 조회
        val usages = usageRepository.findByCustomerAndPeriod(cmd.customerId, cmd.period)
        if (usages.isEmpty()) {
            log.info(
                "no usage for customer={} period={}, skipping",
                cmd.customerId, cmd.period,
            )
            return InitiateOutcome.Skipped(
                SettlementResult.skipped(cmd.customerId, cmd.period, "no usage"),
            )
        }

        // 4. 적용 가능한 가격 정책 + 스냅샷
        //    as-of 로 period 의 시작이 아닌 toExclusive() (= 다음 달 1일 00:00) 를 넘긴다.
        //    findEffective 는 effectiveFrom <= at 인 plan 중 최신을 고르는데, 기간 끝을 기준으로
        //    잡아야 그 달 안에 발효된 가장 최근 plan 까지 포함된다 (월 초 기준이면 월 중 변경분 누락).
        val plan = pricingPlanRepository.findEffective(cmd.customerId, cmd.period.toExclusive())
            .orElseThrow {
                IllegalStateException("no pricing plan for customer ${cmd.customerId}")
            }
        val snapshot = plan.snapshot(clock.instant())

        // 5. 청구서 생성 + 발행 (ISSUED). charge 이전에 커밋되어 invoice.id 가 안정적으로 확정된다.
        val lines = usages.map { u ->
            val amount = snapshot.calculate(u.resourceType, u.totalQuantity)
            val desc = describe(u)
            InvoiceLine(u.resourceType, u.totalQuantity, amount, desc)
        }
        val invoice = Invoice.draft(cmd.customerId, cmd.period, lines, snapshot, clock)
        invoice.issue(clock)
        invoiceRepository.save(invoice)

        // Audit — invoice 발행은 회계상 최초 매출 인식 시점. 회계 감사 / 분쟁 대응 1차 근거.
        audit.log(
            AuditActor.system("settlement-service"),
            AuditAction.INVOICE_ISSUED,
            "Invoice",
            invoice.id.toString(),
            null,
            AuditPayloads.`object`()
                .put("customerId", cmd.customerId.value)
                .put("period", cmd.period.toKey())
                .put("total", invoice.total.amount)
                .put("currency", invoice.total.currency.currencyCode)
                .build(),
            null,
        )
        return InitiateOutcome.Issued(invoice)
    }

    /**
     * Phase 2 — 외부 PG 결제 (트랜잭션 밖). 일시/영구 실패는 false 로 흡수하고 invoice 는 ISSUED 로
     * 남긴다. 실제 PG 어댑터는 invoice.id 를 멱등키로 사용해 재청구 시 중복 과금을 막아야 한다.
     */
    private fun attemptCharge(invoice: Invoice): Boolean =
        try {
            paymentClient.charge(invoice)
        } catch (e: PaymentClient.PaymentFatalException) {
            log.warn("payment fatal for invoice={}: {}", invoice.id, e.message)
            false
        } catch (e: RuntimeException) {
            log.warn("payment transient failure for invoice={}: {}", invoice.id, e.message)
            false
        }

    private fun markInvoicePaid(cmd: RunSettlementCommand, invoice: Invoice) {
        invoice.markPaid(clock)
        invoiceRepository.save(invoice)
        audit.log(
            AuditActor.system("settlement-service"),
            AuditAction.INVOICE_PAID,
            "Invoice",
            invoice.id.toString(),
            null,
            AuditPayloads.`object`()
                .put("customerId", cmd.customerId.value)
                .put("amount", invoice.total.amount)
                .build(),
            null,
        )
    }

    private fun describe(usage: AggregatedUsage): String =
        "${usage.resourceType} × ${usage.totalQuantity} (${usage.eventCount} events)"

    private sealed interface InitiateOutcome {
        data class Issued(val invoice: Invoice) : InitiateOutcome

        data class Skipped(val result: SettlementResult) : InitiateOutcome
    }

    companion object {
        private val log = LoggerFactory.getLogger(RunSettlementService::class.java)
    }
}
