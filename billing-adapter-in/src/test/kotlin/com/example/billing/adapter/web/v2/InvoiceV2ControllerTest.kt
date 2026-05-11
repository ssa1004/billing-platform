package com.example.billing.adapter.web.v2

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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.util.Currency
import java.util.Optional
import java.util.UUID

/**
 * InvoiceV2Controller 단위 테스트 (ADR-0031).
 *
 * v1 과 동일한 도메인 객체에서 추가 필드 가 정확히 전달되는지 검증.
 */
class InvoiceV2ControllerTest {

    private val krw = Currency.getInstance("KRW")
    private val now: Instant = Instant.parse("2026-05-09T00:00:00Z")
    private val repo: InvoiceRepository = mock()
    private val controller = InvoiceV2Controller(repo)

    @Test
    fun `get returns v2 response with appliedCredit and amountDue`() {
        val invoice = anInvoiceWithCredit(applied = BigDecimal("300"))
        whenever(repo.findById(any())).thenReturn(Optional.of(invoice))

        val response = controller.get(invoice.id().toString())

        assertThat(response.statusCode.value()).isEqualTo(200)
        val body = response.body!!
        assertThat(body.total.amount).isEqualByComparingTo("1000")
        assertThat(body.total.currency).isEqualTo("KRW")
        assertThat(body.appliedCredit.amount).isEqualByComparingTo("300")
        assertThat(body.amountDue.amount).isEqualByComparingTo("700")  // 1000 - 300
    }

    @Test
    fun `listByCustomer filters by currency when query provided`() {
        val krwInvoice = anInvoiceWithCredit(applied = BigDecimal.ZERO)
        whenever(repo.findByCustomer(any(), any())).thenReturn(listOf(krwInvoice))

        val list = controller.listByCustomer("c-1", limit = 20, currency = "USD")
        assertThat(list).isEmpty()

        val all = controller.listByCustomer("c-1", limit = 20, currency = null)
        assertThat(all).hasSize(1)
    }

    private fun anInvoiceWithCredit(applied: BigDecimal): Invoice {
        val line = Money.of(BigDecimal("1000"), krw)
        val invoiceLine = InvoiceLine(ResourceType.API_CALL, 1L, line, "1 call")
        val snapshot = PricingSnapshot.of(
            UUID.randomUUID(),
            "p1",
            listOf(Tier(ResourceType.API_CALL, null, line)),
            now,
        )
        return Invoice.restore(
            UUID.randomUUID(),
            CustomerId.of("c-1"),
            BillingPeriod.of(YearMonth.of(2026, 5)),
            listOf(invoiceLine),
            line,
            snapshot,
            now,
            InvoiceStatus.ISSUED,
            Money.of(applied, krw),
            now,
            now.plusSeconds(60),
            null,
            0L,
        )
    }
}
