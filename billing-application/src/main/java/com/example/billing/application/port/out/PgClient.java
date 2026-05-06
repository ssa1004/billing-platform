package com.example.billing.application.port.out;

import com.example.billing.domain.payment.PaymentMethod;
import com.example.billing.domain.shared.Money;

/**
 * 외부 PG (Payment Gateway) 호출 port. Resilience4j Circuit Breaker + Retry 적용 (ADR-0008).
 *
 * <p>구현체:</p>
 * <ul>
 *   <li>{@code RestClientPgClient} — 운영 (Spring RestClient + CB + Retry)</li>
 *   <li>{@code MockPgClient} — 로컬 dev (항상 승인, FAIL_ 키로 실패 시뮬레이션)</li>
 * </ul>
 */
public interface PgClient {

    AuthorizeResult authorize(AuthorizeRequest request);

    RefundResult refund(RefundRequest request);

    record AuthorizeRequest(String idempotencyKey, Money amount, PaymentMethod method, String orderId) {}

    record AuthorizeResult(boolean approved, String pgTransactionId, String errorCode, String errorMessage) {
        public static AuthorizeResult approved(String pgTxId) {
            return new AuthorizeResult(true, pgTxId, null, null);
        }
        public static AuthorizeResult rejected(String code, String msg) {
            return new AuthorizeResult(false, null, code, msg);
        }
    }

    record RefundRequest(String pgTransactionId, Money amount, String reason) {}

    record RefundResult(boolean approved, String pgRefundId, String errorMessage) {
        public static RefundResult approved(String pgRefundId) { return new RefundResult(true, pgRefundId, null); }
        public static RefundResult rejected(String msg) { return new RefundResult(false, null, msg); }
    }
}
