package com.example.billing.application.command

import com.example.billing.domain.settlement.BillingPeriod
import com.example.billing.domain.shared.CustomerId
import org.assertj.core.api.Assertions.assertThat
import java.util.UUID
import org.junit.jupiter.api.Test

/**
 * [SettlementResult] 팩토리의 의미 검증 — 순수 값 객체라 Spring / Mockito 불필요.
 *
 * 기존 `RunSettlementServiceTest` 는 서비스 흐름을 mock 으로 검증하지만 결과 DTO 자체의
 * 플래그·메시지 계약은 다루지 않는다. settlement 집계 결과의 skipped / success(paid) /
 * success(unpaid) 세 상태를 여기서 락다운한다.
 */
class SettlementResultTest {

    private val customer = CustomerId.of("acme-corp")
    private val period = BillingPeriod.of(2026, 5)

    @Test
    fun `skipped 는 alreadyProcessed 플래그와 사유를 담고 invoiceId 가 없다`() {
        val result = SettlementResult.skipped(customer, period, "no usage")

        assertThat(result.alreadyProcessed).isTrue()
        assertThat(result.paymentSucceeded).isFalse()
        assertThat(result.invoiceId).isNull()
        assertThat(result.message).isEqualTo("no usage")
    }

    @Test
    fun `success 결제 성공이면 paid 메시지`() {
        val invoiceId = UUID.randomUUID()
        val result = SettlementResult.success(customer, period, invoiceId, paymentSucceeded = true)

        assertThat(result.alreadyProcessed).isFalse()
        assertThat(result.paymentSucceeded).isTrue()
        assertThat(result.invoiceId).isEqualTo(invoiceId)
        assertThat(result.message).isEqualTo("paid")
    }

    @Test
    fun `success 결제 실패면 invoice 발행됨 메시지 (집계는 성공)`() {
        val invoiceId = UUID.randomUUID()
        val result = SettlementResult.success(customer, period, invoiceId, paymentSucceeded = false)

        assertThat(result.alreadyProcessed).isFalse()
        assertThat(result.paymentSucceeded).isFalse()
        assertThat(result.invoiceId).isEqualTo(invoiceId)
        assertThat(result.message).isEqualTo("invoice issued, payment failed")
    }
}
