package com.example.billing.adapter.out.pg

import com.example.billing.application.port.out.PgClient.AuthorizeRequest
import com.example.billing.application.port.out.PgClient.RefundRequest
import com.example.billing.domain.payment.PaymentMethod
import com.example.billing.domain.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Currency

/** MockPgClient 의 idempotencyKey 패턴 기반 성공/실패 시뮬레이션 검증. */
class MockPgClientTest {

    private val client = MockPgClient()

    @Test
    fun authorize_normalKey_returnsApproved() {
        val result = client.authorize(
            AuthorizeRequest("normal-key", Money.of(1000, KRW), PaymentMethod.CARD, "order-1"),
        )

        assertThat(result.approved).isTrue()
        assertThat(result.pgTransactionId).startsWith("mock-pg-")
    }

    @Test
    fun authorize_failKey_returnsRejected() {
        val result = client.authorize(
            AuthorizeRequest("FAIL_test-key", Money.of(1000, KRW), PaymentMethod.CARD, "order-1"),
        )

        assertThat(result.approved).isFalse()
        assertThat(result.errorCode).isEqualTo("MOCK_FAIL")
    }

    @Test
    fun refund_alwaysApproved() {
        val result = client.refund(RefundRequest("pg-tx-1", Money.of(1000, KRW), "user request"))

        assertThat(result.approved).isTrue()
        assertThat(result.pgRefundId).startsWith("mock-refund-")
    }

    companion object {
        private val KRW: Currency = Currency.getInstance("KRW")
    }
}
