package com.example.billing.adapter.out.pg;

import com.example.billing.application.port.out.PgClient;
import com.example.billing.domain.shared.Money;
import com.example.billing.domain.payment.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;

/** MockPgClient 의 idempotencyKey 패턴 기반 성공/실패 시뮬레이션 검증. */
class MockPgClientTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private final MockPgClient client = new MockPgClient();

    @Test
    void authorize_normalKey_returnsApproved() {
        var result = client.authorize(new PgClient.AuthorizeRequest(
                "normal-key", Money.of(1000, KRW), PaymentMethod.CARD, "order-1"));

        assertThat(result.approved()).isTrue();
        assertThat(result.pgTransactionId()).startsWith("mock-pg-");
    }

    @Test
    void authorize_failKey_returnsRejected() {
        var result = client.authorize(new PgClient.AuthorizeRequest(
                "FAIL_test-key", Money.of(1000, KRW), PaymentMethod.CARD, "order-1"));

        assertThat(result.approved()).isFalse();
        assertThat(result.errorCode()).isEqualTo("MOCK_FAIL");
    }

    @Test
    void refund_alwaysApproved() {
        var result = client.refund(new PgClient.RefundRequest(
                "pg-tx-1", Money.of(1000, KRW), "user request"));

        assertThat(result.approved()).isTrue();
        assertThat(result.pgRefundId()).startsWith("mock-refund-");
    }
}
