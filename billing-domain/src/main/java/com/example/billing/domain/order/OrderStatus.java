package com.example.billing.domain.order;

import java.util.Set;

/**
 * Order 상태머신.
 *
 * <pre>
 *  CREATED ──► PAID ──► REFUNDED
 *     │
 *     ├──► CANCELLED  (결제 전 취소)
 *     │
 *     └──► FAILED     (결제 시도 실패)
 * </pre>
 *
 * 천이 가능 여부는 {@link #canTransitionTo(OrderStatus)} 로 강제. {@link Order} 가 호출해 invariant 보장.
 */
public enum OrderStatus {
    CREATED,
    PAID,
    REFUNDED,
    CANCELLED,
    FAILED;

    private static final Set<OrderStatus> TERMINAL = Set.of(REFUNDED, CANCELLED, FAILED);

    public boolean isTerminal() { return TERMINAL.contains(this); }

    public boolean canTransitionTo(OrderStatus next) {
        return switch (this) {
            case CREATED -> next == PAID || next == CANCELLED || next == FAILED;
            case PAID    -> next == REFUNDED;
            default      -> false;
        };
    }
}
