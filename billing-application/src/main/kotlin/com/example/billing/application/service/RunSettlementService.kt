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
import org.springframework.transaction.annotation.Transactional

/**
 * 한 customer × 한 BillingPeriod (청구 기간) 의 정산.
 *
 * 핵심 설계:
 *  - **advisory lock** (Postgres 의 이름 기반 잠금, 트랜잭션 끝나면 자동 해제) —
 *    `settlement:<customerId>:<period>` 키로 같은 정산이 두 worker 에서 동시에
 *    시작되지 않도록 직렬화. 트랜잭션 종료 시 자동 해제.
 *  - **멱등성 (idempotency)** — 같은 customer × period 에 이미 invoice 가 있으면 skip.
 *    재실행해도 안전.
 *  - **frozen pricing (요금표 동결)** — 청구서 생성 시점의 PricingSnapshot (그 시점
 *    요금표를 그대로 보관한 값 객체) 을 invoice 에 저장. 요금제가 바뀌어도 과거 청구서
 *    금액은 변하지 않음.
 *  - **partial failure (부분 실패 허용)** — invoice 발행은 성공했지만 결제가 실패한
 *    경우, invoice 는 ISSUED 상태로 남아 있고 별도 retry job 이 잡아서 처리.
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
) : RunSettlementUseCase {

    @Transactional
    override fun run(cmd: RunSettlementCommand): SettlementResult {
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
            return SettlementResult.skipped(
                cmd.customerId, cmd.period,
                "invoice already issued: ${existing.get().id}",
            )
        }

        // 3. 월 사용량 집계 조회
        val usages = usageRepository.findByCustomerAndPeriod(cmd.customerId, cmd.period)
        if (usages.isEmpty()) {
            log.info(
                "no usage for customer={} period={}, skipping",
                cmd.customerId, cmd.period,
            )
            return SettlementResult.skipped(cmd.customerId, cmd.period, "no usage")
        }

        // 4. 적용 가능한 가격 정책 + 스냅샷
        val plan = pricingPlanRepository.findEffective(cmd.customerId, cmd.period.toExclusive())
            .orElseThrow {
                IllegalStateException("no pricing plan for customer ${cmd.customerId}")
            }
        val snapshot = plan.snapshot(clock.instant())

        // 5. 청구서 생성
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

        // 6. 결제 시도 (실패해도 invoice 는 ISSUED 로 남고, 별도 retry job 이 다음에 잡아감)
        val paid: Boolean = try {
            val ok = paymentClient.charge(invoice)
            if (ok) {
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
            ok
        } catch (e: PaymentClient.PaymentFatalException) {
            log.warn("payment fatal for invoice={}: {}", invoice.id, e.message)
            false
        } catch (e: RuntimeException) {
            log.warn("payment transient failure for invoice={}: {}", invoice.id, e.message)
            false
        }

        return SettlementResult.success(cmd.customerId, cmd.period, invoice.id, paid)
    }

    private fun describe(usage: AggregatedUsage): String =
        "${usage.resourceType} × ${usage.totalQuantity} (${usage.eventCount} events)"

    companion object {
        private val log = LoggerFactory.getLogger(RunSettlementService::class.java)
    }
}
