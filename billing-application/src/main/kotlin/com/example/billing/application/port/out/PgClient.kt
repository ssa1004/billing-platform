package com.example.billing.application.port.out

import com.example.billing.domain.payment.PaymentMethod
import com.example.billing.domain.shared.Money

/**
 * 외부 PG (Payment Gateway) 호출 port. Resilience4j Circuit Breaker + Retry 적용 (ADR-0008).
 *
 * 구현체:
 *  - `RestClientPgClient` — 운영 (Spring RestClient + CB + Retry)
 *  - `MockPgClient` — 로컬 dev (항상 승인, FAIL_ 키로 실패 시뮬레이션)
 */
interface PgClient {

    fun authorize(request: AuthorizeRequest): AuthorizeResult

    fun refund(request: RefundRequest): RefundResult

    /**
     * idempotencyKey 로 PG 측 처리 결과 조회. 3-phase 결제/환불 흐름에서 phase 2 (PG 호출) 가
     * 성공한 뒤 phase 3 (DB tx2) 가 깨지면 우리 쪽은 PENDING/REQUESTED 인데 PG 는 이미 처리한
     * 상태가 됩니다. PG-reconciler 가 이 메서드로 PG 의 실제 결과를 다시 끌어와 상태 동기화.
     *
     * 일반적인 PG 는 idempotency key 단위로 결과를 영속 보관하고 lookup endpoint 를 제공
     * 합니다. 우리는 그 endpoint 를 호출하는 thin wrapper.
     *
     * [LookupResult.status] 가 `NOT_FOUND` 면 PG 가 그 idempotency key 로
     * 처리한 적이 없다는 뜻 — 즉 phase 2 에서 PG 호출 자체가 실패했거나 아예 안 갔던 것이라
     * 우리 쪽도 FAILED 로 마감하면 됩니다.
     */
    fun lookup(idempotencyKey: String): LookupResult

    @JvmRecord
    data class AuthorizeRequest(
        val idempotencyKey: String,
        val amount: Money,
        val method: PaymentMethod,
        val orderId: String,
    )

    @JvmRecord
    data class AuthorizeResult(
        val approved: Boolean,
        val pgTransactionId: String?,
        val errorCode: String?,
        val errorMessage: String?,
    ) {
        companion object {
            @JvmStatic
            fun approved(pgTxId: String): AuthorizeResult = AuthorizeResult(true, pgTxId, null, null)

            @JvmStatic
            fun rejected(code: String, msg: String): AuthorizeResult = AuthorizeResult(false, null, code, msg)
        }
    }

    @JvmRecord
    data class RefundRequest(val pgTransactionId: String, val amount: Money, val reason: String)

    @JvmRecord
    data class RefundResult(val approved: Boolean, val pgRefundId: String?, val errorMessage: String?) {
        companion object {
            @JvmStatic
            fun approved(pgRefundId: String): RefundResult = RefundResult(true, pgRefundId, null)

            @JvmStatic
            fun rejected(msg: String): RefundResult = RefundResult(false, null, msg)
        }
    }

    /**
     * PG 측 처리 결과 조회 응답.
     *
     * `status` 의 의미:
     *  - `NOT_FOUND` — PG 에 해당 키 없음 (호출 자체가 안 갔거나 실패).
     *    호출자는 우리 쪽 PENDING/REQUESTED 를 FAILED 로 마감.
     *  - `APPROVED` — PG 승인. `pgReferenceId` 가 PG 발급 식별자
     *    (authorize 면 transaction id, refund 면 refund id).
     *  - `REJECTED` — PG 명시적 거절. `errorCode` / `errorMessage` 채움.
     *  - `IN_PROGRESS` — PG 가 아직 결과를 결정 못 함 (rare). 호출자는 다음 사이클에
     *    다시 lookup 해야 합니다.
     */
    @JvmRecord
    data class LookupResult(
        val status: LookupStatus,
        val pgReferenceId: String?,
        val errorCode: String?,
        val errorMessage: String?,
    ) {
        companion object {
            @JvmStatic
            fun notFound(): LookupResult = LookupResult(LookupStatus.NOT_FOUND, null, null, null)

            @JvmStatic
            fun approved(pgReferenceId: String): LookupResult =
                LookupResult(LookupStatus.APPROVED, pgReferenceId, null, null)

            @JvmStatic
            fun rejected(errorCode: String, errorMessage: String): LookupResult =
                LookupResult(LookupStatus.REJECTED, null, errorCode, errorMessage)

            @JvmStatic
            fun inProgress(): LookupResult = LookupResult(LookupStatus.IN_PROGRESS, null, null, null)
        }
    }

    enum class LookupStatus {
        NOT_FOUND, APPROVED, REJECTED, IN_PROGRESS
    }
}
