package com.example.wallet.domain.order;

import com.example.wallet.domain.shared.Money;

import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Order 애그리거트 루트.
 *
 * <p><b>Invariant</b>:</p>
 * <ul>
 *   <li>items 비어있지 않음, 모든 라인이 동일 통화</li>
 *   <li>totalAmount = sum(item.lineTotal())</li>
 *   <li>상태 천이는 {@link OrderStatus#canTransitionTo} 가 허용한 경우만</li>
 * </ul>
 */
public class Order {

    private final OrderId id;
    private final String buyerId;
    private final List<OrderItem> items;
    private final Money totalAmount;
    private final Currency currency;
    private OrderStatus status;
    private String paymentId;
    private String refundId;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private Order(OrderId id, String buyerId, List<OrderItem> items, Money totalAmount, Currency currency,
                  OrderStatus status, String paymentId, String refundId,
                  Instant createdAt, Instant updatedAt, long version) {
        this.id = id;
        this.buyerId = buyerId;
        this.items = List.copyOf(items);
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.status = status;
        this.paymentId = paymentId;
        this.refundId = refundId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static Order place(String buyerId, List<OrderItem> items, Clock clock) {
        Objects.requireNonNull(buyerId, "buyerId");
        if (buyerId.isBlank()) throw new IllegalArgumentException("buyerId must not be blank");
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("items must not be empty");

        Currency currency = items.get(0).unitPrice().currency();
        Money total = Money.zero(currency);
        for (OrderItem item : items) {
            if (!item.unitPrice().currency().equals(currency)) {
                throw new IllegalArgumentException("all items must share currency: " + currency);
            }
            total = total.add(item.lineTotal());
        }
        Instant now = clock.instant();
        return new Order(
                OrderId.newId(), buyerId, items, total, currency,
                OrderStatus.CREATED, null, null, now, now, 0L
        );
    }

    public static Order restore(OrderId id, String buyerId, List<OrderItem> items,
                                Money totalAmount, Currency currency, OrderStatus status,
                                String paymentId, String refundId,
                                Instant createdAt, Instant updatedAt, long version) {
        return new Order(id, buyerId, items, totalAmount, currency, status,
                paymentId, refundId, createdAt, updatedAt, version);
    }

    public OrderEvents.OrderPlaced toPlacedEvent(Clock clock) {
        return new OrderEvents.OrderPlaced(id, buyerId, totalAmount, clock.instant());
    }

    public OrderEvents.OrderPaid markPaid(String paymentId, Clock clock) {
        transition(OrderStatus.PAID);
        this.paymentId = paymentId;
        this.updatedAt = clock.instant();
        return new OrderEvents.OrderPaid(id, paymentId, totalAmount, updatedAt);
    }

    public OrderEvents.OrderCancelled cancel(String reason, Clock clock) {
        transition(OrderStatus.CANCELLED);
        this.updatedAt = clock.instant();
        return new OrderEvents.OrderCancelled(id, reason, updatedAt);
    }

    public OrderEvents.OrderRefunded markRefunded(String refundId, Clock clock) {
        transition(OrderStatus.REFUNDED);
        this.refundId = refundId;
        this.updatedAt = clock.instant();
        return new OrderEvents.OrderRefunded(id, refundId, totalAmount, updatedAt);
    }

    public OrderEvents.OrderFailed markFailed(String reason, Clock clock) {
        transition(OrderStatus.FAILED);
        this.updatedAt = clock.instant();
        return new OrderEvents.OrderFailed(id, reason, updatedAt);
    }

    private void transition(OrderStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalOrderTransitionException(status, next);
        }
        this.status = next;
    }

    // Getters
    public OrderId id() { return id; }
    public String buyerId() { return buyerId; }
    public List<OrderItem> items() { return items; }
    public Money totalAmount() { return totalAmount; }
    public Currency currency() { return currency; }
    public OrderStatus status() { return status; }
    public String paymentId() { return paymentId; }
    public String refundId() { return refundId; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
