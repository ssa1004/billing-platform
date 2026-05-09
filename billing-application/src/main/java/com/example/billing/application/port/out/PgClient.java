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

    /**
     * idempotencyKey 로 PG 측 처리 결과 조회. 3-phase 결제/환불 흐름에서 phase 2 (PG 호출) 가
     * 성공한 뒤 phase 3 (DB tx2) 가 깨지면 우리 쪽은 PENDING/REQUESTED 인데 PG 는 이미 처리한
     * 상태가 됩니다. PG-reconciler 가 이 메서드로 PG 의 실제 결과를 다시 끌어와 상태 동기화.
     *
     * <p>일반적인 PG 는 idempotency key 단위로 결과를 영속 보관하고 lookup endpoint 를 제공
     * 합니다. 우리는 그 endpoint 를 호출하는 thin wrapper.</p>
     *
     * <p>{@link LookupResult#status()} 가 {@code NOT_FOUND} 면 PG 가 그 idempotency key 로
     * 처리한 적이 없다는 뜻 — 즉 phase 2 에서 PG 호출 자체가 실패했거나 아예 안 갔던 것이라
     * 우리 쪽도 FAILED 로 마감하면 됩니다.</p>
     */
    LookupResult lookup(String idempotencyKey);

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

    /**
     * PG 측 처리 결과 조회 응답.
     *
     * <p>{@code status} 의 의미:
     * <ul>
     *   <li>{@code NOT_FOUND} — PG 에 해당 키 없음 (호출 자체가 안 갔거나 실패).
     *       호출자는 우리 쪽 PENDING/REQUESTED 를 FAILED 로 마감.</li>
     *   <li>{@code APPROVED} — PG 승인. {@code pgReferenceId} 가 PG 발급 식별자
     *       (authorize 면 transaction id, refund 면 refund id).</li>
     *   <li>{@code REJECTED} — PG 명시적 거절. {@code errorCode} / {@code errorMessage} 채움.</li>
     *   <li>{@code IN_PROGRESS} — PG 가 아직 결과를 결정 못 함 (rare). 호출자는 다음 사이클에
     *       다시 lookup 해야 합니다.</li>
     * </ul>
     */
    record LookupResult(LookupStatus status, String pgReferenceId,
                        String errorCode, String errorMessage) {
        public static LookupResult notFound() {
            return new LookupResult(LookupStatus.NOT_FOUND, null, null, null);
        }
        public static LookupResult approved(String pgReferenceId) {
            return new LookupResult(LookupStatus.APPROVED, pgReferenceId, null, null);
        }
        public static LookupResult rejected(String errorCode, String errorMessage) {
            return new LookupResult(LookupStatus.REJECTED, null, errorCode, errorMessage);
        }
        public static LookupResult inProgress() {
            return new LookupResult(LookupStatus.IN_PROGRESS, null, null, null);
        }
    }

    enum LookupStatus {
        NOT_FOUND, APPROVED, REJECTED, IN_PROGRESS
    }
}
