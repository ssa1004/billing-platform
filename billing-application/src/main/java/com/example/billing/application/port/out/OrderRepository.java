package com.example.billing.application.port.out;

import com.example.billing.domain.order.Order;
import com.example.billing.domain.order.OrderId;

import java.util.Optional;

public interface OrderRepository {

    void save(Order order);

    Optional<Order> findById(OrderId id);
}
