package com.example.billing.application.service

import com.example.billing.application.port.out.InvoiceRepository
import com.example.billing.domain.invoice.Invoice
import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.shared.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.TreeMap

/**
 * Aged receivables 분석 — 미수 invoice 를 (customer × currency) 별 + aging bucket 별로 집계.
 *
 * 회계 / collection 팀이 보는 표 형태:
 * ```
 *   customer        | currency | 0-30일 | 31-60일 | 61-90일 | 90+일  | total
 *   acme-corp       | KRW      | 1,000  | 500     | 0       | 0      | 1,500
 *   widget-inc      | USD      | 0      | 200     | 800     | 3,000  | 4,000
 *   global-co       | KRW      | 100    | 0       | 0       | 0      | 100
 *   global-co       | USD      | 0      | 50      | 0       | 0      | 50
 * ```
 *
 * 90+일 이 큰 row 는 collection workflow (계정 정지, 법무 송장, 손실 처리) 대상.
 *
 * **왜 (customer, currency) 로 그룹핑 하는가**: 한 customer 가 KRW 와 USD invoice 를
 * 동시에 갖는 다중 통화 케이스에서, 한 bucket 에 두 통화를 더하면 [Money.add] 가 currency
 * mismatch 로 throw → 보고서 전체가 죽습니다. 통화 단위로 row 를 분리하면 이런 사고 없이 각
 * 통화의 미수가 따로 집계됩니다 (환산은 별도 FX 도메인 책임).
 */
@Service
open class AgedReceivablesService(
    private val invoiceRepository: InvoiceRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    open fun report(): Report {
        val now = clock.instant()
        val unpaid = invoiceRepository.findUnpaid(now, FETCH_LIMIT)
        val byCustomerCurrency = HashMap<BucketKey, AgingBuckets>()
        for (inv in unpaid) {
            val key = BucketKey(inv.customerId, inv.total.currency)
            byCustomerCurrency.getOrPut(key) { AgingBuckets(key.currency) }
                .add(inv, now)
        }
        // amountDue 가 모두 0 인 (customer × currency) row (전 invoice 가 credit 으로 상계된
        // 경우) 는 제외 — collection 화면에 노이즈만 됨.
        val sorted = TreeMap<BucketKey, AgingBuckets>(
            compareBy<BucketKey> { it.customerId.value }
                .thenBy { it.currency.currencyCode },
        )
        byCustomerCurrency.forEach { (k, v) ->
            if (!v.total.isZero) sorted[k] = v
        }
        return Report(now, sorted)
    }

    @JvmRecord
    data class Report(val asOf: Instant, val byCustomerCurrency: Map<BucketKey, AgingBuckets>)

    /** customer × currency 복합 키. 다중 통화 invoice 를 가진 customer 도 안전히 분리 집계. */
    @JvmRecord
    data class BucketKey(val customerId: CustomerId, val currency: Currency)

    class AgingBuckets internal constructor(currency: Currency) {

        /** 0-30 일 */
        @get:JvmName("current")
        var current: Money = Money.zero(currency)
            private set

        @get:JvmName("over30")
        var over30: Money = Money.zero(currency)
            private set

        @get:JvmName("over60")
        var over60: Money = Money.zero(currency)
            private set

        @get:JvmName("over90")
        var over90: Money = Money.zero(currency)
            private set

        @get:JvmName("currency")
        val currency: Currency = currency

        @get:JvmName("total")
        val total: Money get() = current.add(over30).add(over60).add(over90)

        internal fun add(inv: Invoice, now: Instant) {
            val due: Instant = inv.dueAt ?: inv.createdAt
            val days = ChronoUnit.DAYS.between(due, now)
            // amountDue = total - appliedCredit. credit 이 일부 적용된 invoice 는 이미 그만큼
            // 받은 셈이라 receivable 에서 빼고 잡아야 한다. total() 을 쓰면 collection 팀이
            // 보는 미수 금액이 부풀려져 잘못된 액션 (계정 정지 / 법무 송장) 으로 이어진다.
            val amount = inv.amountDue()
            if (amount.isZero) return // 전액 credit 으로 상계된 invoice 는 표에 안 잡음
            when {
                days <= 30 -> current = current.add(amount)
                days <= 60 -> over30 = over30.add(amount)
                days <= 90 -> over60 = over60.add(amount)
                else -> over90 = over90.add(amount)
            }
        }
    }

    companion object {
        private const val FETCH_LIMIT = 10_000
    }
}
