package com.example.billing.domain.shared;

/**
 * Ledger entry / Outbox event 가 가리키는 외부 참조 (orderId, paymentId, refundId 등).
 *
 * <p>{@code type} 으로 카테고리, {@code id} 로 식별. 도메인 코드에서는 typed 형태로,
 * DB 에는 {@code reference_type} + {@code reference_id} 두 컬럼으로 저장.</p>
 */
public record Reference(Type type, String id) {

    public enum Type {
        ORDER, PAYMENT, REFUND, ADJUSTMENT, EXTERNAL
    }

    public static Reference order(String orderId) { return new Reference(Type.ORDER, orderId); }
    public static Reference payment(String paymentId) { return new Reference(Type.PAYMENT, paymentId); }
    public static Reference refund(String refundId) { return new Reference(Type.REFUND, refundId); }
    public static Reference adjustment(String description) { return new Reference(Type.ADJUSTMENT, description); }
}
