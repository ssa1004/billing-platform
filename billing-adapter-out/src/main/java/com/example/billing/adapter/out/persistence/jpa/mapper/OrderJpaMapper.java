package com.example.billing.adapter.out.persistence.jpa.mapper;

import com.example.billing.adapter.out.persistence.jpa.entity.OrderItemJpaEntity;
import com.example.billing.adapter.out.persistence.jpa.entity.OrderJpaEntity;
import com.example.billing.domain.order.Order;
import com.example.billing.domain.order.OrderId;
import com.example.billing.domain.order.OrderItem;
import com.example.billing.domain.order.OrderStatus;
import com.example.billing.domain.shared.Money;

import java.util.Currency;
import java.util.List;
import java.util.UUID;

public final class OrderJpaMapper {

    private OrderJpaMapper() {}

    public static OrderJpaEntity toEntity(Order o) {
        OrderJpaEntity e = new OrderJpaEntity();
        e.setId(o.id().value());
        e.setBuyerId(o.buyerId());
        e.setTotalAmount(o.totalAmount().amount());
        e.setCurrency(o.currency().getCurrencyCode());
        e.setStatus(o.status().name());
        e.setPaymentId(o.paymentId() != null ? UUID.fromString(o.paymentId()) : null);
        e.setRefundId(o.refundId() != null ? UUID.fromString(o.refundId()) : null);
        e.setCreatedAt(o.createdAt());
        e.setUpdatedAt(o.updatedAt());
        e.setVersion(o.version());

        List<OrderItemJpaEntity> items = o.items().stream()
                .map(item -> {
                    OrderItemJpaEntity ie = new OrderItemJpaEntity();
                    ie.setOrderId(o.id().value());
                    ie.setSku(item.sku());
                    ie.setQuantity(item.quantity());
                    ie.setUnitPrice(item.unitPrice().amount());
                    return ie;
                })
                .toList();
        e.getItems().clear();
        e.getItems().addAll(items);
        return e;
    }

    public static Order toDomain(OrderJpaEntity e) {
        Currency currency = Currency.getInstance(e.getCurrency());
        List<OrderItem> items = e.getItems().stream()
                .map(ie -> new OrderItem(ie.getSku(), ie.getQuantity(), Money.of(ie.getUnitPrice(), currency)))
                .toList();
        return Order.restore(
                new OrderId(e.getId()),
                e.getBuyerId(),
                items,
                Money.of(e.getTotalAmount(), currency),
                currency,
                OrderStatus.valueOf(e.getStatus()),
                e.getPaymentId() != null ? e.getPaymentId().toString() : null,
                e.getRefundId() != null ? e.getRefundId().toString() : null,
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getVersion()
        );
    }
}
