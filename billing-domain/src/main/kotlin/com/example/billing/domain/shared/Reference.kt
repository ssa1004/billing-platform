package com.example.billing.domain.shared

/**
 * Ledger entry / Outbox event 가 가리키는 외부 참조 (orderId, paymentId, refundId 등).
 *
 * [type] 으로 카테고리, [id] 로 식별. 도메인 코드에서는 typed 형태로, DB 에는 `reference_type`
 * + `reference_id` 두 컬럼으로 저장.
 *
 * `@JvmRecord` 로 Java 측에서 기존 record accessor (`r.type()` / `r.id()`) + 생성자
 * (`new Reference(Type.ORDER, "x")`) 를 그대로 사용 가능.
 */
@JvmRecord
data class Reference(val type: Type, val id: String) {

    enum class Type {
        ORDER, PAYMENT, REFUND, ADJUSTMENT, EXTERNAL
    }

    companion object {
        @JvmStatic
        fun order(orderId: String): Reference = Reference(Type.ORDER, orderId)

        @JvmStatic
        fun payment(paymentId: String): Reference = Reference(Type.PAYMENT, paymentId)

        @JvmStatic
        fun refund(refundId: String): Reference = Reference(Type.REFUND, refundId)

        @JvmStatic
        fun adjustment(description: String): Reference = Reference(Type.ADJUSTMENT, description)
    }
}
